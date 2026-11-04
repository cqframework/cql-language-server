package org.opencds.cqf.cql.ls.server.validation.spike

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport
import ca.uhn.fhir.context.support.IValidationSupport
import ca.uhn.fhir.parser.LenientErrorHandler
import ca.uhn.fhir.validation.FhirValidator
import ca.uhn.fhir.validation.ResultSeverityEnum
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport
import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport
import org.hl7.fhir.common.hapi.validation.support.UnknownCodeSystemWarningValidationSupport
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r5.elementmodel.Manager
import org.hl7.fhir.r5.utils.validation.constants.ReferenceValidationPolicy
import org.hl7.fhir.utilities.npm.FilesystemPackageCacheManager
import org.hl7.fhir.utilities.npm.NpmPackage
import org.hl7.fhir.utilities.validation.ValidationMessage
import org.hl7.fhir.validation.ValidationEngine
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidationIssue
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidationSeverity
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText

// SPIKE ONLY: throwaway prototype code for evaluating the HL7 core validator. Not production code.

data class PackageLoad(val id: String, val version: String, val wasCached: Boolean, val ok: Boolean, val note: String = "")

/** Resolves [roots] plus their transitive dependencies through the package cache (downloads if missing). */
fun resolvePackages(roots: List<Pair<String, String>>): Pair<List<NpmPackage>, List<PackageLoad>> {
    val fpcm = FilesystemPackageCacheManager.Builder().build()
    val cacheDir = File(System.getProperty("user.home"), ".fhir/packages")
    val loaded = mutableListOf<NpmPackage>()
    val log = mutableListOf<PackageLoad>()
    val seenIds = mutableSetOf<String>()
    val queue = ArrayDeque(roots)
    while (queue.isNotEmpty()) {
        val (id, version) = queue.removeFirst()
        if (id == "hl7.fhir.r4.core" || !seenIds.add(id)) continue
        val wasCached = File(cacheDir, "$id#$version").exists()
        try {
            val pkg = fpcm.loadPackage(id, version)
            loaded.add(pkg)
            log.add(PackageLoad(id, pkg.version(), wasCached, true))
            pkg.dependencies().forEach { dep ->
                val (dId, dVer) = dep.split("#", limit = 2).let { it[0] to it.getOrElse(1) { "current" } }
                queue.add(dId to dVer)
            }
        } catch (e: Exception) {
            log.add(PackageLoad(id, version, wasCached, false, "${e.javaClass.simpleName}: ${e.message?.take(200)}"))
        }
    }
    return loaded to log
}

private fun severityOf(level: ValidationMessage.IssueSeverity?): ValidationSeverity =
    when (level) {
        ValidationMessage.IssueSeverity.FATAL, ValidationMessage.IssueSeverity.ERROR -> ValidationSeverity.ERROR
        ValidationMessage.IssueSeverity.WARNING -> ValidationSeverity.WARNING
        else -> ValidationSeverity.INFORMATION
    }

/** (a) ValidationEngine, configured the way validator-wrapper does it, but with no tx server and our fetcher. */
class EngineA(
    packageIds: List<String>,
    vocabularyDir: Path?,
    val advisor: ReferenceCheckingPolicyAdvisor,
) : SpikeEngine {
    override val name = "a:ValidationEngine"
    val fetcher = NoNetworkFetcher()
    val loadNotes = mutableListOf<String>()
    private val coreStart = System.nanoTime()
    private val engine: ValidationEngine =
        ValidationEngine.ValidationEngineBuilder()
            .withVersion("4.0.1")
            .withNoTerminologyServer()
            .withCanRunWithoutTerminologyServer(true)
            .withUserAgent("cql-ls-validator-spike")
            .fromSource("hl7.fhir.r4.core#4.0.1")

    init {
        loadNotes.add("core hl7.fhir.r4.core: ${(System.nanoTime() - coreStart) / 1_000_000} ms")
        for (p in packageIds) {
            val t = System.nanoTime()
            try {
                engine.igLoader.loadIg(engine.igs, engine.binaries, p, true)
                loadNotes.add("$p: ok ${(System.nanoTime() - t) / 1_000_000} ms")
            } catch (e: Exception) {
                loadNotes.add("$p: FAILED ${e.javaClass.simpleName}: ${e.message?.take(200)}")
            }
        }
        if (vocabularyDir != null) {
            val t = System.nanoTime()
            try {
                engine.igLoader.loadIg(engine.igs, engine.binaries, vocabularyDir.toString(), true)
                loadNotes.add("vocabulary: ok ${(System.nanoTime() - t) / 1_000_000} ms")
            } catch (e: Exception) {
                loadNotes.add("vocabulary: FAILED ${e.javaClass.simpleName}: ${e.message?.take(200)}")
            }
        }
        engine.setFetcher(fetcher)
        engine.setPolicyAdvisor(advisor)
        engine.setAnyExtensionsAllowed(true)
        engine.setNoExtensibleBindingMessages(true)
    }

    override fun validateFile(
        file: Path,
        index: CaseIndex,
    ): List<ValidationIssue> {
        fetcher.index = index
        val messages = mutableListOf<ValidationMessage>()
        return try {
            engine.validate(file.readBytes(), Manager.FhirFormat.JSON, emptyList(), messages)
            messages.map { it.toIssue(file) }
        } catch (e: Exception) {
            messages.map { it.toIssue(file) } + exceptionIssue(file, e)
        }
    }
}

/** (b) HAPI FhirValidator + FhirInstanceValidator with a ValidationSupportChain fed from NpmPackage folders. */
class EngineB(
    packages: List<NpmPackage>,
    vocabularyDir: Path?,
    val advisor: ReferenceCheckingPolicyAdvisor,
) : SpikeEngine {
    override val name = "b:FhirInstanceValidator"
    val fetcher = NoNetworkFetcher()
    val loadNotes = mutableListOf<String>()
    private val ctx: FhirContext = FhirContext.forR4Cached()
    private val validator: FhirValidator

    init {
        val parser = ctx.newJsonParser().setParserErrorHandler(LenientErrorHandler(false))
        val prePopulated = PrePopulatedValidationSupport(ctx)
        val types = arrayOf("StructureDefinition", "ValueSet", "CodeSystem")
        for (pkg in packages) {
            val t = System.nanoTime()
            var ok = 0
            var failed = 0
            for (res in pkg.listResources(*types)) {
                try {
                    pkg.load("package", res).use { prePopulated.addResource(parser.parseResource(it) as IBaseResource) }
                    ok++
                } catch (e: Exception) {
                    failed++
                }
            }
            loadNotes.add("${pkg.name()}#${pkg.version()}: $ok loaded, $failed failed, ${(System.nanoTime() - t) / 1_000_000} ms")
        }
        if (vocabularyDir != null) {
            val t = System.nanoTime()
            var ok = 0
            var failed = 0
            Files.walk(vocabularyDir).use { s -> s.filter { it.name.endsWith(".json") }.toList() }.forEach {
                try {
                    prePopulated.addResource(parser.parseResource(it.readText()) as IBaseResource)
                    ok++
                } catch (e: Exception) {
                    failed++
                }
            }
            loadNotes.add("vocabulary: $ok loaded, $failed failed, ${(System.nanoTime() - t) / 1_000_000} ms")
        }
        val unknownCs = UnknownCodeSystemWarningValidationSupport(ctx)
        unknownCs.setNonExistentCodeSystemSeverity(IValidationSupport.IssueSeverity.WARNING)
        val chain =
            ValidationSupportChain(
                DefaultProfileValidationSupport(ctx),
                prePopulated,
                SnapshotGeneratingValidationSupport(ctx),
                CommonCodeSystemsTerminologyService(ctx),
                InMemoryTerminologyServerValidationSupport(ctx),
                unknownCs,
            )
        val instanceValidator = FhirInstanceValidator(chain)
        instanceValidator.setValidatorResourceFetcher(fetcher)
        instanceValidator.setValidatorPolicyAdvisor(advisor)
        instanceValidator.setAnyExtensionsAllowed(true)
        instanceValidator.setNoExtensibleWarnings(true)
        validator = ctx.newValidator().apply { registerValidatorModule(instanceValidator) }
    }

    override fun validateFile(
        file: Path,
        index: CaseIndex,
    ): List<ValidationIssue> {
        fetcher.index = index
        return try {
            validator.validateWithResult(file.readText()).messages.map {
                ValidationIssue(
                    severity =
                        when (it.severity) {
                            ResultSeverityEnum.FATAL, ResultSeverityEnum.ERROR -> ValidationSeverity.ERROR
                            ResultSeverityEnum.WARNING -> ValidationSeverity.WARNING
                            else -> ValidationSeverity.INFORMATION
                        },
                    code = it.messageId ?: "(none)",
                    message = it.message,
                    filePath = file.toString(),
                    jsonPath = it.locationString,
                    line = it.locationLine,
                )
            }
        } catch (e: Exception) {
            listOf(exceptionIssue(file, e))
        }
    }
}

/** Mapper: core validator message to the existing extension-facing ValidationIssue model. */
fun ValidationMessage.toIssue(file: Path): ValidationIssue =
    ValidationIssue(
        severity = severityOf(level),
        code = messageId ?: "(none)",
        message = message,
        filePath = file.toString(),
        jsonPath = location,
        line = line.takeIf { it > 0 },
    )

private fun exceptionIssue(
    file: Path,
    e: Exception,
) = ValidationIssue(
    severity = ValidationSeverity.ERROR,
    code = "EXCEPTION:${e.javaClass.simpleName}",
    message = e.message?.take(300) ?: e.javaClass.name,
    filePath = file.toString(),
    line = null,
)

/** Rebuild the advisor with a different local reference policy. */
fun ReferenceCheckingPolicyAdvisor.withPolicy(p: ReferenceValidationPolicy) = apply { localPolicy = p }
