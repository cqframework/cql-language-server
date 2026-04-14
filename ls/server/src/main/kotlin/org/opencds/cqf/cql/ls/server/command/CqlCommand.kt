package org.opencds.cqf.cql.ls.server.command

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import ca.uhn.fhir.repository.IRepository
import kotlinx.io.files.Path
import org.cqframework.cql.cql2elm.CqlTranslatorOptions
import org.cqframework.cql.cql2elm.DefaultLibrarySourceProvider
import org.cqframework.cql.cql2elm.DefaultModelInfoProvider
import org.cqframework.fhir.npm.NpmProcessor
import org.cqframework.fhir.utilities.IGContext
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.fhir.instance.model.api.IBase
import org.hl7.fhir.instance.model.api.IBaseDatatype
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r5.context.ILoggingService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.repository.ig.standard.IgStandardRepository
import org.opencds.cqf.fhir.cql.CqlOptions
import org.opencds.cqf.fhir.cql.Engines
import org.opencds.cqf.fhir.cql.EvaluationSettings
import org.opencds.cqf.fhir.cql.engine.retrieve.RetrieveSettings
import org.opencds.cqf.fhir.cql.engine.retrieve.RetrieveSettings.PROFILE_MODE
import org.opencds.cqf.fhir.cql.engine.retrieve.RetrieveSettings.SEARCH_FILTER_MODE
import org.opencds.cqf.fhir.cql.engine.retrieve.RetrieveSettings.TERMINOLOGY_FILTER_MODE
import org.opencds.cqf.fhir.cql.engine.terminology.TerminologySettings
import org.opencds.cqf.fhir.cql.engine.terminology.TerminologySettings.CODE_LOOKUP_MODE
import org.opencds.cqf.fhir.cql.engine.terminology.TerminologySettings.VALUESET_EXPANSION_MODE
import org.opencds.cqf.fhir.cql.engine.terminology.TerminologySettings.VALUESET_MEMBERSHIP_MODE
import org.opencds.cqf.fhir.cql.engine.terminology.TerminologySettings.VALUESET_PRE_EXPANSION_MODE
import org.opencds.cqf.fhir.utility.repository.ProxyRepository
import org.slf4j.LoggerFactory
import picocli.CommandLine
import picocli.CommandLine.ArgGroup
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Paths
import java.util.concurrent.Callable

@Command(name = "cql", mixinStandardHelpOptions = true)
class CqlCommand : Callable<Int> {
    companion object {
        private val log = LoggerFactory.getLogger(CqlCommand::class.java)
    }

    @Option(names = ["-fv", "--fhir-version"], required = true)
    var fhirVersion: String = ""

    @Option(names = ["-op", "--options-path"])
    var optionsPath: String? = null

    @ArgGroup(multiplicity = "0..1", exclusive = false)
    var namespace: NamespaceParameter? = null

    class NamespaceParameter {
        @Option(names = ["-nn", "--namespace-name"])
        var namespaceName: String? = null

        @Option(names = ["-nu", "--namespace-uri"])
        var namespaceUri: String? = null
    }

    @Option(names = ["-rd", "--root-dir"])
    var rootDir: String? = null

    @Option(names = ["-ig", "--ig-path"])
    var igPath: String? = null

    @ArgGroup(multiplicity = "1..*", exclusive = false)
    var libraries: MutableList<LibraryParameter> = mutableListOf()

    class LibraryParameter {
        @Option(names = ["-lu", "--library-url"], required = true)
        var libraryUrl: String? = null

        @Option(names = ["-ln", "--library-name"], required = true)
        var libraryName: String = ""

        @Option(names = ["-lv", "--library-version"])
        var libraryVersion: String? = null

        @Option(names = ["-t", "--terminology-url"])
        var terminologyUrl: String? = null

        @ArgGroup(multiplicity = "0..1", exclusive = false)
        var model: ModelParameter? = null

        @ArgGroup(multiplicity = "0..*", exclusive = false)
        var parameters: MutableList<ParameterParameter> = mutableListOf()

        @Option(names = ["-e", "--expression"])
        var expression: Array<String>? = null

        @ArgGroup(multiplicity = "0..1", exclusive = false)
        var context: ContextParameter? = null

        class ContextParameter {
            @Option(names = ["-c", "--context"])
            var contextName: String? = null

            @Option(names = ["-cv", "--context-value"])
            var contextValue: String? = null
        }

        class ModelParameter {
            @Option(names = ["-m", "--model"])
            var modelName: String? = null

            @Option(names = ["-mu", "--model-url"])
            var modelUrl: String? = null
        }

        class ParameterParameter {
            @Option(names = ["-p", "--parameter"])
            var parameterName: String? = null

            @Option(names = ["-pv", "--parameter-value"])
            var parameterValue: String? = null
        }
    }

    @Suppress("removal") // TODO: Missed a spot upstream in the CQL library
    private class Logger : ILoggingService {
        private val log = LoggerFactory.getLogger(Logger::class.java)

        override fun logMessage(s: String) {
            log.warn(s)
        }

        override fun logDebugMessage(
            logCategory: ILoggingService.LogCategory,
            s: String,
        ) {
            log.debug("{}: {}", logCategory, s)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun isDebugLogging(): Boolean = log.isDebugEnabled
    }

    private fun toVersionNumber(fhirVersion: FhirVersionEnum): String {
        return when (fhirVersion) {
            FhirVersionEnum.R4 -> "4.0.1"
            FhirVersionEnum.R5 -> "5.0.0-ballot"
            FhirVersionEnum.DSTU3 -> "3.0.2"
            else -> throw IllegalArgumentException("Unsupported FHIR version $fhirVersion")
        }
    }

    @CommandLine.ParentCommand
    private var parentCommand: CliCommand? = null

    override fun call(): Int {
        val fhirVersionEnum = FhirVersionEnum.valueOf(fhirVersion)
        val fhirContext = FhirContext.forCached(fhirVersionEnum)

        var igContext: IGContext? = null
        var npmProcessor: NpmProcessor? = null
        if (rootDir != null && igPath != null) {
            igContext = IGContext(Logger())
            igContext.initializeFromIg(rootDir, igPath, toVersionNumber(fhirVersionEnum))
        } else if (parentCommand != null && rootDir != null) {
            val pc = parentCommand
            val rd = rootDir
            if (pc != null && rd != null) {
                val rootUri = Uris.parseOrNull(rd)
                val inputUri = rootUri?.let { Uris.addPath(it, "input") }
                val cqlUri = inputUri?.let { Uris.addPath(it, "cql") }
                npmProcessor = cqlUri?.let { pc.igContextManager.getContext(it) }
            }
            if (npmProcessor != null) {
                igContext = npmProcessor.igContext
            }
        }

        if (npmProcessor == null) {
            npmProcessor = NpmProcessor(igContext)
        }

        val cqlOptions = CqlOptions.defaultOptions()

        val optionsPathVal = optionsPath
        if (optionsPathVal != null) {
            val optUri =
                Uris.parseOrNull(optionsPathVal)
                    ?: run {
                        log.warn("Could not parse options path: $optionsPathVal")
                        return 1
                    }
            val op = Path(Paths.get(optUri).toString())
            val options = CqlTranslatorOptions.fromFile(Path(op))
            cqlOptions.setCqlCompilerOptions(options.cqlCompilerOptions)
        }

        val terminologySettings =
            TerminologySettings().apply {
                setValuesetExpansionMode(VALUESET_EXPANSION_MODE.PERFORM_NAIVE_EXPANSION)
                setValuesetPreExpansionMode(VALUESET_PRE_EXPANSION_MODE.USE_IF_PRESENT)
                setValuesetMembershipMode(VALUESET_MEMBERSHIP_MODE.USE_EXPANSION)
                setCodeLookupMode(CODE_LOOKUP_MODE.USE_CODESYSTEM_URL)
            }

        val retrieveSettings =
            RetrieveSettings().apply {
                setTerminologyParameterMode(TERMINOLOGY_FILTER_MODE.FILTER_IN_MEMORY)
                setSearchParameterMode(SEARCH_FILTER_MODE.FILTER_IN_MEMORY)
                setProfileMode(PROFILE_MODE.DECLARED)
            }

        val evaluationSettings =
            EvaluationSettings.getDefault().apply {
                setCqlOptions(cqlOptions)
                setTerminologySettings(terminologySettings)
                setRetrieveSettings(retrieveSettings)
                setNpmProcessor(npmProcessor)
            }

        for (library in libraries) {
            // Paths are mixed types
            // IgStandardRepository uses java nio path objects
            // DefaultLibraryServiceProvider uses kotlin path objects
            // Until the language server can be ported to kotlin, the differences will exist
            val libraryUrlVal = library.libraryUrl
            val libraryUri = if (libraryUrlVal != null) Uris.parseOrNull(libraryUrlVal) else null

            val libraryKotlinPath = if (libraryUri != null) Path(Paths.get(libraryUri).toString()) else null

            val modelPath = library.model?.modelUrl?.let { Uris.parseOrNull(it)?.let { u -> Paths.get(u) } }

            val terminologyUrl = library.terminologyUrl
            val terminologyPath = terminologyUrl?.let { Uris.parseOrNull(it)?.let { u -> Paths.get(u) } }

            val repository = createRepository(fhirContext, terminologyPath, modelPath)

            val engine = Engines.forRepository(repository, evaluationSettings)

            val kPath = libraryKotlinPath
            if (library.libraryUrl != null && kPath != null) {
                val provider = DefaultLibrarySourceProvider(kPath)
                engine.environment
                    .libraryManager?.librarySourceLoader
                    ?.registerProvider(provider)

                val modelProvider = DefaultModelInfoProvider(kPath)
                engine.environment
                    .libraryManager?.modelManager
                    ?.modelInfoLoader
                    ?.registerModelInfoProvider(modelProvider)
            }

            val identifier = VersionedIdentifier().withId(library.libraryName)

            val contextParameter: org.apache.commons.lang3.tuple.Pair<String?, Any?>? =
                library.context?.let { ctx ->
                    org.apache.commons.lang3.tuple.Pair.of(ctx.contextName, ctx.contextValue)
                }

            val expressions = library.expression?.toSet()
            val result =
                if (expressions != null) {
                    engine.evaluate(identifier, expressions, contextParameter)
                } else {
                    engine.evaluate(identifier, contextParameter)
                }

            writeResult(result)
        }

        return 0
    }

    private fun createRepository(
        fhirContext: FhirContext,
        terminologyPath: java.nio.file.Path?,
        modelPath: java.nio.file.Path?,
    ): IRepository {
        if (terminologyPath == null && modelPath == null) {
            return NoOpRepository(fhirContext)
        }

        val data: IRepository = if (modelPath != null) IgStandardRepository(fhirContext, modelPath) else NoOpRepository(fhirContext)
        val terminology: IRepository =
            if (terminologyPath != null) {
                IgStandardRepository(
                    fhirContext,
                    terminologyPath,
                )
            } else {
                NoOpRepository(fhirContext)
            }

        return ProxyRepository(data, data, terminology)
    }

    @Suppress("java:S106") // We are intending to output to the console here as a CLI tool
    private fun writeResult(result: org.opencds.cqf.cql.engine.execution.EvaluationResult) {
        for ((key, value) in result.expressionResults) {
            println("$key=${tempConvert(value?.value())}")
        }
        println()
    }

    private fun tempConvert(value: Any?): String {
        if (value == null) return "null"

        return when (value) {
            is Iterable<*> -> {
                val items = value.joinToString(", ") { tempConvert(it) }
                "[$items]"
            }
            is IBaseResource ->
                value.fhirType() +
                    if (value.idElement != null && value.idElement.hasIdPart()) "(id=${value.idElement.idPart})" else ""
            is IBase -> value.fhirType()
            is IBaseDatatype -> value.fhirType()
            else -> value.toString()
        }
    }
}
