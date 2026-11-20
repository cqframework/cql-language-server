package org.opencds.cqf.cql.ls.server.validation.profile

import org.hl7.fhir.r5.elementmodel.Manager
import org.hl7.fhir.r5.utils.validation.constants.BestPracticeWarningLevel
import org.hl7.fhir.utilities.validation.ValidationMessage
import org.hl7.fhir.validation.ValidationEngine
import org.opencds.cqf.cql.ls.server.validation.testcase.IssueSource
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidationIssue
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidationSeverity
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.readBytes

/** What one engine loads: `id#version` packages plus the IG's own vocabulary and measures. */
data class ProfileEngineSpec(
    val igRoot: Path,
    val packages: List<String>,
) {
    val vocabularyDir: Path get() = ProfileValidationPaths.vocabularyDir(igRoot)
    val measureDir: Path get() = ProfileValidationPaths.measureDir(igRoot)
}

object ProfileValidationPaths {
    fun vocabularyDir(igRoot: Path): Path = igRoot.resolve("input").resolve("vocabulary")

    fun measureDir(igRoot: Path): Path = igRoot.resolve("input").resolve("resources").resolve("measure")

    /** The nearest directory at or above [path] that contains an `ig.ini`, or null. */
    fun findIgRoot(path: Path): Path? {
        var dir: Path? = path.toAbsolutePath().normalize().let { if (Files.isDirectory(it)) it else it.parent }
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("ig.ini"))) return dir
            dir = dir.parent
        }
        return null
    }
}

/**
 * [ResourceProfileValidator] backed by the HL7 core `ValidationEngine` (the engine behind the IG
 * Publisher and validator-wrapper), with no terminology server and a no-network fetcher. Calls are
 * serialized: the fetcher's index and the advisor's mode are per call, and one engine is not known
 * to be thread-safe.
 */
class CoreEngineProfileValidator private constructor(
    private val engine: ValidationEngine,
) : ResourceProfileValidator {
    companion object {
        private val log = LoggerFactory.getLogger(CoreEngineProfileValidator::class.java)
        private const val CORE_PACKAGE = "hl7.fhir.r4.core#4.0.1"

        /** Issue code for a file the engine threw on; the custom checks stay for that file. */
        const val VALIDATION_FAILED_CODE = "CORE_VALIDATION_FAILED"

        // Warm-up resources: the first validation of each type pays for lazy snapshot generation.
        private val WARM_UP =
            listOf(
                """{"resourceType":"Patient","id":"warm-up","meta":{"profile":["http://hl7.org/fhir/us/qicore/StructureDefinition/qicore-patient"]},""" +
                    """"identifier":[{"system":"http://example.org","value":"1"}],"name":[{"family":"Warm","given":["Up"]}],"gender":"female","birthDate":"1980-01-01"}""",
                """{"resourceType":"Encounter","id":"warm-up","meta":{"profile":["http://hl7.org/fhir/us/qicore/StructureDefinition/qicore-encounter"]},""" +
                    """"status":"finished","class":{"system":"http://terminology.hl7.org/CodeSystem/v3-ActCode","code":"IMP"},""" +
                    """"type":[{"coding":[{"system":"http://snomed.info/sct","code":"183452005"}]}],"subject":{"reference":"Patient/warm-up"},""" +
                    """"period":{"start":"2026-01-01T00:00:00Z","end":"2026-01-02T00:00:00Z"}}""",
            )

        /** Builds and warms up an engine. Slow (30 s to 2 min); throws if a package can't be loaded. */
        fun build(spec: ProfileEngineSpec): CoreEngineProfileValidator {
            val start = System.nanoTime()
            val engine =
                ValidationEngine.ValidationEngineBuilder()
                    .withVersion("4.0.1")
                    .withNoTerminologyServer()
                    .withCanRunWithoutTerminologyServer(true)
                    .withUserAgent("cql-language-server")
                    .fromSource(CORE_PACKAGE)
            for (pkg in spec.packages) {
                engine.igLoader.loadIg(engine.igs, engine.binaries, pkg, true)
            }
            for (dir in listOf(spec.vocabularyDir, spec.measureDir)) {
                if (Files.isDirectory(dir)) engine.igLoader.loadIg(engine.igs, engine.binaries, dir.toString(), true)
            }
            engine.setBestPracticeLevel(BestPracticeWarningLevel.Ignore)
            engine.setAnyExtensionsAllowed(true)
            engine.setNoExtensibleBindingMessages(true)
            val validator = CoreEngineProfileValidator(engine)
            validator.warmUp()
            log.info("Profile validator for {} ready in {} ms (packages {})", spec.igRoot, (System.nanoTime() - start) / 1_000_000, spec.packages)
            return validator
        }
    }

    private val fetcher = NoNetworkFetcher()
    private val advisor = ReferencePolicyAdvisor()
    private val lock = ReentrantLock()

    init {
        engine.setFetcher(fetcher)
        engine.setPolicyAdvisor(advisor)
    }

    override fun validate(
        files: List<Path>,
        index: CaseResourceIndex,
        mode: EngineMode,
        checkReferences: Boolean,
    ): List<ValidationIssue> =
        lock.withLock {
            fetcher.index = index
            advisor.mode = mode
            advisor.checkReferences = checkReferences
            try {
                files.flatMap { file -> validateBytes(file, file.readBytes()) }
            } finally {
                fetcher.index = CaseResourceIndex.EMPTY
            }
        }

    private fun validateBytes(
        file: Path,
        bytes: ByteArray,
    ): List<ValidationIssue> {
        val messages = mutableListOf<ValidationMessage>()
        return try {
            engine.validate(bytes, Manager.FhirFormat.JSON, emptyList(), messages)
            messages.map { it.toCoreIssue(file) }
        } catch (e: Exception) {
            log.warn("Profile validation of {} failed: {}", file, e.message)
            messages.map { it.toCoreIssue(file) } +
                ValidationIssue(
                    severity = ValidationSeverity.WARNING,
                    code = VALIDATION_FAILED_CODE,
                    message = "FHIR Validator could not validate this file: ${e.message ?: e.javaClass.simpleName}",
                    filePath = file.toString(),
                    line = 1,
                    source = IssueSource.CORE,
                )
        }
    }

    private fun warmUp() {
        lock.withLock {
            for (mode in EngineMode.entries) {
                advisor.mode = mode
                advisor.checkReferences = true
                WARM_UP.forEach { validateBytes(Path.of("warm-up.json"), it.toByteArray()) }
            }
        }
    }
}
