package org.opencds.cqf.cql.ls.server.command

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import ca.uhn.fhir.repository.IRepository
import kotlinx.io.Source
import kotlinx.io.files.Path
import org.cqframework.cql.cql2elm.CqlTranslatorOptions
import org.cqframework.cql.cql2elm.DefaultLibrarySourceProvider
import org.cqframework.cql.cql2elm.DefaultModelInfoProvider
import org.cqframework.cql.cql2elm.LibrarySourceProvider
import org.cqframework.cql.cql2elm.model.CompiledLibrary
import org.cqframework.cql.cql2elm.quick.FhirLibrarySourceProvider
import org.cqframework.fhir.npm.NpmProcessor
import org.cqframework.fhir.utilities.IGContext
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.fhir.instance.model.api.IBase
import org.hl7.fhir.instance.model.api.IBaseDatatype
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r5.context.ILoggingService
import org.opencds.cqf.cql.engine.debug.BreakpointHandler
import org.opencds.cqf.cql.engine.execution.CqlEngine
import org.opencds.cqf.cql.engine.execution.trace.ExpressionDefTraceFrame
import org.opencds.cqf.cql.engine.execution.trace.SubExpressionTraceFrame
import org.opencds.cqf.cql.engine.execution.trace.TraceFrame
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Converters
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.provider.ContentServiceModelInfoProvider
import org.opencds.cqf.cql.ls.server.provider.FederatedLibrarySourceProvider
import org.opencds.cqf.cql.ls.server.repository.ig.standard.FederatedTerminologyRepo
import org.opencds.cqf.cql.ls.server.repository.ig.standard.IgStandardRepository
import org.opencds.cqf.cql.ls.server.utility.VersionReader
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
import java.math.BigDecimal
import java.nio.file.Paths
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

object CqlEvaluator {
    private val log = LoggerFactory.getLogger(CqlEvaluator::class.java)

    private const val PARAM_EVAL_LIBRARY_ID = "__ParamEval__"

    /** In-memory [LibrarySourceProvider] that serves a single CQL source string by library id. */
    private class CqlSourceStringProvider(
        private val libraryId: String,
        private val source: String,
    ) : LibrarySourceProvider {
        override fun getLibrarySource(libraryIdentifier: VersionedIdentifier): Source? {
            return if (libraryIdentifier.id == libraryId) Converters.stringToSource(source) else null
        }
    }

    /**
     * When [parameterType] contains "DateTime", replaces bare date literals (`@YYYY-MM-DD` not
     * already followed by `T`) with DateTime literals (`@YYYY-MM-DDT`) so that the mini CQL
     * library evaluates the expression as `Interval<DateTime>` rather than `Interval<Date>`.
     *
     * CQL distinguishes Date (`@2024-01-01`) from DateTime (`@2024-01-01T`); a bare date cannot
     * be passed where a DateTime is expected, causing a runtime type-mismatch error.
     */
    private fun coerceDateLiterals(
        value: String,
        parameterType: String,
    ): String {
        if (!parameterType.contains("DateTime")) return value
        // Append 'T' to any @YYYY-MM-DD literal not already followed by 'T'
        return value.replace(Regex("@(\\d{4}-\\d{2}-\\d{2})(?!T)")) { match -> "@${match.groupValues[1]}T" }
    }

    /**
     * Parses a string as a CQL DateTime value.
     * Expected format: @YYYY-MM-DDTHH:MM:SS.fffffffZ or similar ISO 8601 format
     */
    private fun parseCqlDateTimeValue(value: String): org.hl7.fhir.r5.model.DateTimeType {
        // Remove the '@' prefix if present
        val cleanValue = if (value.startsWith("@")) value.substring(1) else value
        return org.hl7.fhir.r5.model.DateTimeType(cleanValue)
    }

    /**
     * Parses a string as a CQL Date value.
     * Expected format: @YYYY-MM-DD
     */
    private fun parseCqlDateValue(value: String): org.hl7.fhir.r5.model.DateType {
        // Remove the '@' prefix if present
        val cleanValue = if (value.startsWith("@")) value.substring(1) else value
        return org.hl7.fhir.r5.model.DateType(cleanValue)
    }

    /**
     * Parses a string as a CQL Time value.
     * Expected format: @THH:MM:SS.fffffff
     */
    private fun parseCqlTimeValue(value: String): org.hl7.fhir.r5.model.TimeType {
        // Remove the '@' prefix if present
        val cleanValue = if (value.startsWith("@")) value.substring(1) else value
        return org.hl7.fhir.r5.model.TimeType(cleanValue)
    }

    /**
     * Parses a string as a CQL Quantity value.
     * Expected format: number 'unit' (e.g., '5.4'mg)
     */
    private fun parseCqlQuantityValue(value: String): org.hl7.fhir.r5.model.Quantity {
        val quantity = org.hl7.fhir.r5.model.Quantity()
        // Simple parsing - in a real implementation, this would be more robust
        val parts = value.trim().split("'".toRegex(), limit = 2)
        if (parts.size == 2) {
            val numericPart = parts[0].trim()
            val unitPart = parts[1].trim().removeSuffix("'")
            try {
                quantity.value = numericPart.toBigDecimal()
                quantity.unit = unitPart
                quantity.code = unitPart
            } catch (e: NumberFormatException) {
                // If parsing fails, set as string and let the engine handle it
                quantity.value = BigDecimal.ZERO
                quantity.unit = value
                quantity.code = value
            }
        } else {
            // No unit specified, treat as just a number
            try {
                quantity.value = value.toBigDecimal()
                quantity.unit = "1"
                quantity.code = "1"
            } catch (e: NumberFormatException) {
                quantity.value = BigDecimal.ZERO
                quantity.unit = value
                quantity.code = value
            }
        }
        return quantity
    }

    /**
     * Parses a string as a CQL Interval value.
     * Expected format: Interval[lower, upper] where lower and upper are datetime/date values
     */
    private fun parseCqlIntervalValue(
        parameterName: String,
        parameterValue: String,
        pointType: String,
    ): Any {
        // This is a simplified implementation - a full implementation would parse the interval properly
        // For now, we'll return the raw value and let the engine handle parsing
        return parameterValue
    }

    /**
     * Evaluates each parameter value expression using a throw-away CQL library, returning a map of
     * parameter name → typed runtime value suitable for passing to [org.opencds.cqf.cql.engine.execution.CqlEngine].
     *
     * Values are written as CQL literal expressions in [ParameterRequest.parameterValue]
     * (e.g. `Interval[@2024-01-01, @2024-12-31]`, `'HMO'`, `true`). Only language-level
     * primitives are supported; expressions that reference FHIR model types will fail and
     * result in an empty map for that library.
     *
     * Date literals are automatically coerced to DateTime when [ParameterRequest.parameterType]
     * contains "DateTime" (see [coerceDateLiterals]).
     */
    private fun parseParameterValues(
        fhirContext: FhirContext,
        evaluationSettings: EvaluationSettings,
        parameters: List<ParameterRequest>,
    ): Map<String, Any?>? {
        if (parameters.isEmpty()) return null

        val defines =
            parameters.mapIndexed { i, p ->
                val coercedValue = coerceDateLiterals(p.parameterValue, p.parameterType)
                "define \"__v${i}__\": $coercedValue"
            }
        val cqlSource = "library $PARAM_EVAL_LIBRARY_ID version '1'\n" + defines.joinToString("\n")

        val paramEngine = Engines.forRepository(NoOpRepository(fhirContext), evaluationSettings)
        paramEngine.environment.libraryManager!!.librarySourceLoader.registerProvider(
            CqlSourceStringProvider(PARAM_EVAL_LIBRARY_ID, cqlSource),
        )

        val identifier = VersionedIdentifier().withId(PARAM_EVAL_LIBRARY_ID).withVersion("1")
        return try {
            val evalResults =
                paramEngine.evaluate {
                    library(identifier)
                }
            val libResult =
                evalResults.getResultFor(identifier)
                    ?: throw (
                        evalResults.getExceptionFor(identifier)
                            ?: RuntimeException("No result or exception found for library ${identifier.id}")
                    )
            val result = mutableMapOf<String, Any?>()
            parameters.forEachIndexed { i, p ->
                libResult.expressionResults["__v${i}__"]?.value?.let { result[p.parameterName] = it }
            }
            result
        } catch (e: Exception) {
            log.warn(
                "Failed to evaluate parameter values for [${parameters.joinToString { it.parameterName }}]: ${e.message}",
            )
            null
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

    private fun createRepository(
        fhirContext: FhirContext,
        terminologyRepo: IRepository,
        modelPath: java.nio.file.Path?,
    ): IRepository {
        val data: IRepository =
            if (modelPath != null) IgStandardRepository(fhirContext, modelPath) else NoOpRepository(fhirContext)
        // ProxyRepository routes search(ValueSet/CodeSystem) → terminology, everything else → data.
        return ProxyRepository(data, data, terminologyRepo)
    }

    private fun coerceParameters(parameters: List<ParameterRequest>): MutableMap<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        for (param in parameters) {
            val value: Any? =
                when (param.parameterType?.lowercase()) {
                    "integer" ->
                        param.parameterValue.toIntOrNull()
                            ?: run {
                                log.warn(
                                    "Parameter '${param.parameterName}': could not parse '${param.parameterValue}' as Integer. Passing as String.",
                                )
                                param.parameterValue
                            }
                    "decimal" ->
                        param.parameterValue.toBigDecimalOrNull()
                            ?: run {
                                log.warn(
                                    "Parameter '${param.parameterName}': could not parse '${param.parameterValue}' as Decimal. Passing as String.",
                                )
                                param.parameterValue
                            }
                    "boolean" ->
                        param.parameterValue.toBooleanStrictOrNull()
                            ?: run {
                                log.warn(
                                    "Parameter '${param.parameterName}': could not parse '${param.parameterValue}' as Boolean. Passing as String.",
                                )
                                param.parameterValue
                            }
                    "datetime" ->
                        try {
                            parseCqlDateTimeValue(param.parameterValue)
                        } catch (e: Exception) {
                            log.warn(
                                "Parameter '${param.parameterName}': could not parse '${param.parameterValue}' as DateTime: ${e.message}. Passing as String.",
                            )
                            param.parameterValue
                        }
                    "date" ->
                        try {
                            parseCqlDateValue(param.parameterValue)
                        } catch (e: Exception) {
                            log.warn(
                                "Parameter '${param.parameterName}': could not parse '${param.parameterValue}' as Date: ${e.message}. Passing as String.",
                            )
                            param.parameterValue
                        }
                    "time" ->
                        try {
                            parseCqlTimeValue(param.parameterValue)
                        } catch (e: Exception) {
                            log.warn(
                                "Parameter '${param.parameterName}': could not parse '${param.parameterValue}' as Time: ${e.message}. Passing as String.",
                            )
                            param.parameterValue
                        }
                    "quantity" ->
                        try {
                            parseCqlQuantityValue(param.parameterValue)
                        } catch (e: Exception) {
                            log.warn(
                                "Parameter '${param.parameterName}': could not parse '${param.parameterValue}' as Quantity: ${e.message}. Passing as String.",
                            )
                            param.parameterValue
                        }
                    "interval<datetime>" -> parseCqlIntervalValue(param.parameterName ?: "", param.parameterValue, "datetime")
                    "interval<date>" -> parseCqlIntervalValue(param.parameterName ?: "", param.parameterValue, "date")
                    "string", null -> param.parameterValue
                    else -> {
                        log.warn(
                            "Parameter '${param.parameterName}': type '${param.parameterType}' is not a recognised CQL type. Passing as String.",
                        )
                        param.parameterValue
                    }
                }
            result[param.parameterName] = value
        }
        return result
    }

    private fun formatValue(value: Any?): String {
        if (value == null) return "null"

        return when (value) {
            is Iterable<*> -> {
                val items = value.joinToString(", ") { formatValue(it) }
                "[$items]"
            }
            is IBaseResource ->
                value.fhirType() +
                    if (value.idElement != null && value.idElement.hasIdPart()) "(id=${value.idElement.idPart})" else ""
            is IBaseDatatype -> value.fhirType()
            is IBase -> value.fhirType()
            else -> value.toString()
        }
    }

    private fun buildCqlOptions(optionsPath: String?): CqlOptions {
        val cqlOptions = CqlOptions.defaultOptions()
        if (optionsPath != null) {
            val op = Path(Paths.get(Uris.parseOrNull(optionsPath)!!).toString())
            val translatorOptions = CqlTranslatorOptions.fromFile(op)
            cqlOptions.setCqlCompilerOptions(translatorOptions.cqlCompilerOptions)
        }
        return cqlOptions
    }

    private fun buildEvaluationSettings(
        cqlOptions: CqlOptions,
        npmProcessor: NpmProcessor?,
    ): EvaluationSettings {
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

        return EvaluationSettings.getDefault().apply {
            setCqlOptions(cqlOptions)
            setTerminologySettings(terminologySettings)
            setRetrieveSettings(retrieveSettings)
            setNpmProcessor(npmProcessor)
        }
    }

    private fun evaluateBatch(
        batch: MutableList<LibraryRequest>,
        fhirContext: FhirContext,
        npmProcessor: NpmProcessor?,
        contentService: ContentService,
        terminologyRepo: IRepository,
        evaluationSettings: EvaluationSettings,
        sharedLibraryCache: ConcurrentHashMap<VersionedIdentifier, CompiledLibrary>,
        cqlRootUri: java.net.URI?,
        igContextManager: IgContextManager,
        libraryResolutionManager: LibraryResolutionManager,
        detailedTracing: Boolean = false,
        defineOrderOut: MutableList<String>? = null,
        breakpointHandler: BreakpointHandler? = null,
    ): Pair<List<LibraryResult>, List<DetailedExpressionResult>> {
        val libraryResults = mutableListOf<LibraryResult>()
        val detailedResults = mutableListOf<DetailedExpressionResult>()

        for (libraryRequest in batch) {
            try {
                val libraryUri = Uris.parseOrNull(libraryRequest.libraryUri)
                val libraryKotlinPath = if (libraryUri != null) Path(Paths.get(libraryUri).toString()) else null

                val modelPath = libraryRequest.model?.modelUri?.let { Paths.get(Uris.parseOrNull(it)!!) }

                // evaluationSettings is constructed once per evaluate() call above — its
                // librarySourceProviders list is empty at this point.
                // Clear it defensively before adding our providers so the priority order
                // (local → npm → bundled FHIRHelpers) is guaranteed even if that ever changes.
                evaluationSettings.librarySourceProviders.clear()
                if (libraryUri != null) {
                    evaluationSettings.librarySourceProviders.add(
                        FederatedLibrarySourceProvider(libraryUri, contentService, npmProcessor),
                    )
                } else if (libraryKotlinPath != null) {
                    evaluationSettings.librarySourceProviders.add(
                        DefaultLibrarySourceProvider(libraryKotlinPath),
                    )
                }

                val repository = createRepository(fhirContext, terminologyRepo, modelPath)
                if (detailedTracing) {
                    evaluationSettings.cqlOptions.cqlEngineOptions.setDetailedTracingEnabled(true)
                }
                val engine = Engines.forRepository(repository, evaluationSettings)

                // Set up npm packages on the engine's LibraryManager when NpmProcessor was not set
                // on evaluationSettings (i.e. no workspace-root ig.ini, as in multi-project
                // workspaces).  Use the per-library URI so that each library's own project ig.ini
                // is found — cqlRootUri points at the VS Code workspace root which has no ig.ini
                // in multi-project layouts.  This mirrors CqlCompilationManager.createLibraryManager().
                val igSetupUri = libraryUri ?: cqlRootUri
                if (npmProcessor == null && igSetupUri != null) {
                    igContextManager.setupLibraryManager(igSetupUri, engine.environment.libraryManager!!)
                }

                // Always register workspace project namespaces. Local projects declared with
                // version "dev" are not in npm, but ARE in the LibraryResolutionManager index
                // built from workspace ig.ini files. Must match what CqlCompilationManager does.
                libraryResolutionManager.registerWorkspaceNamespaces(engine.environment.libraryManager!!)

                // Register bundled FHIRHelpers last (lowest priority — fallback only).
                engine.environment.libraryManager!!.librarySourceLoader
                    .registerProvider(FhirLibrarySourceProvider())

                // Model info providers have no ordering concern; register after engine creation.
                if (libraryUri != null) {
                    engine.environment.libraryManager!!.modelManager.modelInfoLoader.registerModelInfoProvider(
                        ContentServiceModelInfoProvider(libraryUri, contentService),
                    )
                } else if (libraryKotlinPath != null) {
                    engine.environment.libraryManager!!.modelManager.modelInfoLoader.registerModelInfoProvider(
                        DefaultModelInfoProvider(libraryKotlinPath),
                    )
                }

                val params = parseParameterValues(fhirContext, evaluationSettings, libraryRequest.parameters)

                val identifier = VersionedIdentifier().withId(libraryRequest.libraryName)

                val parameters = params

                if (breakpointHandler != null) {
                    engine.state.breakpointHandler = breakpointHandler
                }
                val evaluationResults =
                    engine.evaluate {
                        if (!parameters.isNullOrEmpty()) this.parameters = parameters
                        if (libraryRequest.context != null) {
                            contextParameter =
                                Pair(
                                    libraryRequest.context.contextName,
                                    libraryRequest.context.contextValue,
                                )
                        }
                        library(identifier)
                    }
                val result =
                    evaluationResults.getResultFor(identifier)
                        ?: throw (
                            evaluationResults.getExceptionFor(identifier)
                                ?: RuntimeException("No result or exception found for library ${identifier.id}")
                        )
                val expressions =
                    result.expressionResults.map { (key, value) ->
                        ExpressionResult(key, formatValue(value.value))
                    }

                if (detailedTracing && result.trace != null) {
                    for (frame in result.trace!!.frames) {
                        if (frame is ExpressionDefTraceFrame) {
                            val defineName = frame.element.name ?: continue
                            collectSubExpressions(frame.subframes, defineName, detailedResults)
                        }
                    }
                    if (defineOrderOut != null) {
                        collectDefineOrder(result.trace!!.frames, mutableSetOf(), defineOrderOut)
                    }
                }

                val overriddenNames = libraryRequest.parameters.map { it.parameterName }.toSet()
                val compiledLib =
                    engine.environment.libraryManager!!
                        .compiledLibraries.entries
                        .firstOrNull { it.key.id == identifier.id }
                        ?.value
                val defaultParams: List<DefaultParameterResult> =
                    if (compiledLib != null) {
                        (compiledLib.library?.parameters?.def ?: emptyList())
                            .filter { paramDef ->
                                paramDef.default != null && paramDef.name !in overriddenNames
                            }
                            .mapNotNull { paramDef ->
                                val name = paramDef.name ?: return@mapNotNull null
                                val stateValue = engine.state.parameters["${identifier.id}.$name"]
                                if (stateValue != null) {
                                    DefaultParameterResult(name, formatValue(stateValue))
                                } else {
                                    null
                                }
                            }
                    } else {
                        emptyList()
                    }

                libraryResults.add(LibraryResult(libraryRequest.libraryName, expressions, defaultParams))
            } catch (e: Exception) {
                log.error("Error evaluating library ${libraryRequest.libraryName} for context ${libraryRequest.context?.contextValue}", e)
                libraryResults.add(
                    LibraryResult(
                        libraryRequest.libraryName,
                        listOf(ExpressionResult("Error", e.message ?: e.javaClass.simpleName)),
                    ),
                )
            }
        }

        return libraryResults to detailedResults
    }

    internal fun collectDefineOrder(
        frames: List<TraceFrame>,
        seen: MutableSet<String>,
        result: MutableList<String>,
    ) {
        for (frame in frames) {
            if (frame is ExpressionDefTraceFrame) {
                val name = frame.element.name ?: continue
                collectDefineOrder(frame.subframes, seen, result)
                if (seen.add(name)) result.add(name)
            } else if (frame is SubExpressionTraceFrame) {
                collectDefineOrder(frame.subframes, seen, result)
            }
        }
    }

    private fun collectSubExpressions(
        frames: List<TraceFrame>,
        parentDefine: String,
        results: MutableList<DetailedExpressionResult>,
    ) {
        for (frame in frames) {
            if (frame is SubExpressionTraceFrame) {
                val locator = frame.element.locator
                if (locator != null) {
                    results.add(
                        DetailedExpressionResult(null, formatValue(frame.result), locator, parentDefine),
                    )
                }
                log.debug(
                    "sub-expr define={} locator={} type={} value={}",
                    parentDefine,
                    locator,
                    frame.result?.javaClass?.simpleName,
                    frame.result,
                )
                collectSubExpressions(frame.subframes, parentDefine, results)
            } else if (frame is ExpressionDefTraceFrame) {
                // Nested defines (e.g. function calls) — recurse with their own name
                collectSubExpressions(frame.subframes, frame.element.name ?: parentDefine, results)
            }
        }
    }

    /**
     * Common implementation for [evaluate] and [evaluateDetailed].
     * When [detailedTracing] is true, the engine records sub-expression results
     * via [org.opencds.cqf.cql.engine.execution.CqlEngine.Options.EnableDetailedTracing]
     * and they are returned alongside the top-level expression results.
     */
    private fun evaluateInternal(
        request: ExecuteCqlRequest,
        contentService: ContentService,
        igContextManager: IgContextManager,
        libraryResolutionManager: LibraryResolutionManager,
        detailedTracing: Boolean = false,
        breakpointHandler: BreakpointHandler? = null,
    ): DetailedEvaluationResult {
        log.debug(
            "{}: fhirVersion={} libraries={} rootDir={}",
            if (detailedTracing) "evaluateDetailed" else "evaluate",
            request.fhirVersion,
            request.libraries.size,
            request.rootDir,
        )
        val fhirContext = FhirContext.forCached(FhirVersionEnum.valueOf(request.fhirVersion))

        var igContext: IGContext? = null
        var npmProcessor: NpmProcessor? = null
        val rootDir = request.rootDir
        val cqlRootUri =
            rootDir?.let { Uris.addPath(Uris.addPath(Uris.parseOrNull(it)!!, "input")!!, "cql") }
        if (rootDir != null) {
            npmProcessor =
                igContextManager.getContext(
                    Uris.addPath(Uris.addPath(Uris.parseOrNull(rootDir)!!, "input")!!, "cql")!!,
                )
            if (npmProcessor != null) {
                igContext = npmProcessor.igContext
            }
        }

        if (npmProcessor == null) {
            npmProcessor = NpmProcessor(igContext)
        }

        val grouped = LinkedHashMap<String, MutableList<LibraryRequest>>()
        for (lib in request.libraries) {
            val key = "${lib.libraryName}|${lib.libraryUri}|${lib.terminologyUri}|${request.optionsPath}"
            grouped.getOrPut(key) { mutableListOf() }.add(lib)
        }

        val terminologyRepo: IRepository =
            run {
                val inputPaths = libraryResolutionManager.getInputDirectories()
                if (inputPaths.isEmpty()) {
                    NoOpRepository(fhirContext)
                } else {
                    FederatedTerminologyRepo(fhirContext, inputPaths)
                }
            }

        val libraryCaches = mutableMapOf<String?, ConcurrentHashMap<VersionedIdentifier, CompiledLibrary>>()
        val allDetailed = mutableListOf<DetailedExpressionResult>()
        val allDefineOrder = mutableListOf<String>()

        val allResults =
            grouped.values.flatMap { batch ->
                val sharedLibraryCache = libraryCaches.getOrPut(request.optionsPath) { ConcurrentHashMap() }
                val evaluationSettings =
                    buildEvaluationSettings(
                        buildCqlOptions(request.optionsPath),
                        NpmProcessor(igContext),
                    ).withLibraryCache(sharedLibraryCache)
                val (results, detailed) =
                    evaluateBatch(
                        batch,
                        fhirContext,
                        npmProcessor,
                        contentService,
                        terminologyRepo,
                        evaluationSettings,
                        sharedLibraryCache,
                        cqlRootUri,
                        igContextManager,
                        libraryResolutionManager,
                        detailedTracing = detailedTracing,
                        defineOrderOut = if (detailedTracing) allDefineOrder else null,
                        breakpointHandler = breakpointHandler,
                    )
                allDetailed.addAll(detailed)
                results
            }

        val versions =
            VersionInfo(
                translator = VersionReader.loadVersion("cql-to-elm-jvm"),
                engine = VersionReader.loadVersion("engine-jvm"),
                clinicalReasoning = VersionReader.loadVersion("cqf-fhir-cql"),
                languageServer = VersionReader.loadVersion("cql-ls-server"),
            )

        return DetailedEvaluationResult(
            ExecuteCqlResponse(allResults, emptyList(), versions),
            allDetailed,
            allDefineOrder,
        )
    }

    /**
     * Evaluates all libraries in the request. Libraries sharing the same libraryName,
     * libraryUri, terminologyUri, and optionsPath are evaluated in a single batch using
     * one engine — CQL is compiled once and the [LibraryManager] cache is reused for all
     * subsequent patients in the batch.
     *
     * Across batches, two resources are shared when their discriminating key matches:
     * - [IgStandardRepository] (terminology): keyed by terminologyUri — the ValueSet
     *   directory is scanned at most once per unique path for the entire request.
     * - [EvaluationSettings] (libraryCache): keyed by optionsPath — compiled ELM for
     *   helper libraries (FHIRHelpers, QICore) produced by batch N is reused by batch N+1
     *   without recompilation. Safe because [VersionedIdentifier] includes the library's
     *   own namespace URI, so libraries with the same name+version always share the same
     *   compiled ELM regardless of which source path they were loaded from.
     */
    fun evaluate(
        request: ExecuteCqlRequest,
        contentService: ContentService,
        igContextManager: IgContextManager,
        libraryResolutionManager: LibraryResolutionManager,
    ): ExecuteCqlResponse {
        return evaluateInternal(
            request,
            contentService,
            igContextManager,
            libraryResolutionManager,
            detailedTracing = false,
        ).response
    }

    /**
     * Evaluates all libraries with detailed tracing enabled. Returns both the top-level
     * expression results and the sub-expression trace results.
     *
     * Sub-expression results are captured via [org.opencds.cqf.cql.engine.execution.CqlEngine.Options.EnableDetailedTracing],
     * which records the runtime value of every non-trivial ELM node. Each sub-expression
     * result includes its source locator (e.g. `"10:12-10:24"`) and the parent define name.
     *
     * The returned [DetailedEvaluationResult.defineOrder] lists define names in
     * dependency-first evaluation order, suitable for sorting debugger snapshots.
     *
     * Use these detailed results in [CqlDebugServer] to support position-based hover
     * evaluation during debugging.
     */
    fun evaluateDetailed(
        request: ExecuteCqlRequest,
        contentService: ContentService,
        igContextManager: IgContextManager,
        libraryResolutionManager: LibraryResolutionManager,
    ): DetailedEvaluationResult {
        return evaluateInternal(
            request,
            contentService,
            igContextManager,
            libraryResolutionManager,
            detailedTracing = true,
        )
    }

    /**
     * Evaluates a single library on a background thread with fine-grained stepping controlled
     * by the given [BreakpointHandler]. The method returns immediately; the handler's
     * [BreakpointHandler.onBeforeExpression] callback is invoked for every ELM expression
     * and may return [org.opencds.cqf.cql.engine.debug.BreakpointAction.PAUSE] to suspend
     * evaluation. Use [BreakpointHandler.release] or the convenience methods on
     * [StreamingBreakpointHandler] to resume stepping.
     */
    fun evaluateStreaming(
        request: ExecuteCqlRequest,
        contentService: ContentService,
        igContextManager: IgContextManager,
        libraryResolutionManager: LibraryResolutionManager,
        breakpointHandler: BreakpointHandler,
        executor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor(),
    ): java.util.concurrent.CompletableFuture<Unit> {
        val future = java.util.concurrent.CompletableFuture<Unit>()
        executor.submit {
            try {
                evaluateInternal(
                    request,
                    contentService,
                    igContextManager,
                    libraryResolutionManager,
                    breakpointHandler = breakpointHandler,
                )
                future.complete(Unit)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }
        return future
    }
}
