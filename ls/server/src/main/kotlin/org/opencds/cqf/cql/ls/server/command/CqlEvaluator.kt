package org.opencds.cqf.cql.ls.server.command

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import ca.uhn.fhir.repository.IRepository
import kotlinx.io.files.Path
import org.cqframework.cql.cql2elm.CqlTranslatorOptions
import org.cqframework.cql.cql2elm.DefaultLibrarySourceProvider
import org.cqframework.cql.cql2elm.DefaultModelInfoProvider
import org.cqframework.cql.cql2elm.model.CompiledLibrary
import org.cqframework.fhir.npm.NpmProcessor
import org.cqframework.fhir.utilities.IGContext
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.fhir.instance.model.api.IBase
import org.hl7.fhir.instance.model.api.IBaseDatatype
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r5.context.ILoggingService
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.provider.ContentServiceModelInfoProvider
import org.opencds.cqf.cql.ls.server.provider.ContentServiceSourceProvider
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
import org.opencds.cqf.fhir.utility.repository.FederatedRepository
import org.opencds.cqf.fhir.utility.repository.ProxyRepository
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Paths
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import org.opencds.cqf.cql.engine.runtime.Date as CqlDate
import org.opencds.cqf.cql.engine.runtime.DateTime as CqlDateTime
import org.opencds.cqf.cql.engine.runtime.Interval as CqlInterval
import org.opencds.cqf.cql.engine.runtime.Quantity as CqlQuantity
import org.opencds.cqf.cql.engine.runtime.Time as CqlTime

object CqlEvaluator {
    private val log = LoggerFactory.getLogger(CqlEvaluator::class.java)

    private fun heapStats(): String {
        val rt = Runtime.getRuntime()
        val usedMB = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
        val maxMB = rt.maxMemory() / 1_048_576
        return "heap=${usedMB}MB/${maxMB}MB"
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

    // Matches CQL interval literals: Interval[/( low, high ]/). Non-greedy to handle null endpoints.
    private val intervalLiteralRegex = Regex("""^Interval([\[(])\s*(.*?)\s*,\s*(.*?)\s*([\])])$""")

    // Matches CQL quantity literals: e.g. 5 'mg', 1.5 'd'
    private val quantityLiteralRegex = Regex("""^(\d+(?:\.\d+)?)\s+'([^']*)'$""")

    /** Strips the leading {@code @} from a CQL temporal literal and constructs a [CqlDateTime]. */
    private fun parseCqlDateTimeValue(literal: String): CqlDateTime =
        CqlDateTime(literal.trimStart('@'), ZoneOffset.UTC)

    /** Strips the leading {@code @} from a CQL date literal and constructs a [CqlDate]. */
    private fun parseCqlDateValue(literal: String): CqlDate =
        CqlDate(literal.trimStart('@'))

    /**
     * Strips the leading {@code @} from a CQL time literal and constructs a [CqlTime].
     * The engine's [CqlTime] string constructor handles the {@code T} prefix.
     */
    private fun parseCqlTimeValue(literal: String): CqlTime =
        CqlTime(literal.trimStart('@'))

    /**
     * Parses a CQL quantity literal ({@code 5 'mg'}) into a [CqlQuantity].
     * Throws [IllegalArgumentException] if the format does not match.
     */
    private fun parseCqlQuantityValue(literal: String): CqlQuantity {
        val match =
            quantityLiteralRegex.find(literal.trim())
                ?: throw IllegalArgumentException("Expected format: <number> '<unit>', got '$literal'")
        return CqlQuantity().withValue(BigDecimal(match.groupValues[1])).withUnit(match.groupValues[2])
    }

    /**
     * Parses a CQL interval literal (e.g. {@code Interval[@2024-01-01, @2024-12-31)}) into a
     * [CqlInterval] whose endpoints are native CQL runtime objects. Falls back to the raw string
     * and logs a warning if the literal cannot be parsed.
     *
     * @param pointType either {@code "datetime"} or {@code "date"} — used to pick the endpoint parser
     */
    private fun parseCqlIntervalValue(
        paramName: String,
        value: String,
        pointType: String,
    ): Any {
        val match =
            intervalLiteralRegex.find(value.trim())
                ?: run {
                    log.warn(
                        "Parameter '$paramName': could not parse interval literal '$value'. Passing as String.",
                    )
                    return value
                }
        val lowClosed = match.groupValues[1] == "["
        val highClosed = match.groupValues[4] == "]"

        fun parseEndpoint(s: String): Any? {
            if (s.equals("null", ignoreCase = true)) return null
            return try {
                when (pointType) {
                    "datetime" -> parseCqlDateTimeValue(s)
                    "date" -> parseCqlDateValue(s)
                    else -> s
                }
            } catch (e: Exception) {
                log.warn(
                    "Parameter '$paramName': could not parse interval endpoint '$s' as $pointType: ${e.message}. Passing as String.",
                )
                s
            }
        }

        return CqlInterval(parseEndpoint(match.groupValues[2]), lowClosed, parseEndpoint(match.groupValues[3]), highClosed)
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

    private fun buildCqlOptions(optionsPath: String?): CqlOptions {
        val cqlOptions = CqlOptions.defaultOptions()
        if (optionsPath != null) {
            val op = Path(Paths.get(Uris.parseOrNull(optionsPath)!!).toString())
            val options = CqlTranslatorOptions.fromFile(Path(op))
            cqlOptions.setCqlCompilerOptions(options.cqlCompilerOptions)
        }
        return cqlOptions
    }

    private fun buildEvaluationSettings(
        cqlOptions: CqlOptions,
        npmProcessor: NpmProcessor,
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
    ): ExecuteCqlResponse {
        log.debug("evaluate: fhirVersion={} libraries={} rootDir={}", request.fhirVersion, request.libraries.size, request.rootDir)
        val fhirContext = FhirContext.forCached(FhirVersionEnum.valueOf(request.fhirVersion))

        var igContext: IGContext? = null
        var npmProcessor: NpmProcessor? = null
        val rootDir = request.rootDir
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

        // Group by batch key — preserves insertion order via LinkedHashMap so results come
        // back in the same order they were sent.
        val grouped = LinkedHashMap<String, MutableList<LibraryRequest>>()
        for (lib in request.libraries) {
            val key = "${lib.libraryName}|${lib.libraryUri}|${lib.terminologyUri}|${request.optionsPath}"
            grouped.getOrPut(key) { mutableListOf() }.add(lib)
        }

        // Shared across batches — created once per unique key for the lifetime of this request.
        // terminologyRepos: the IgStandardRepository.typeResourceCache on a shared instance
        //   means the ValueSet directory is scanned at most once per unique path.
        // libraryCaches: compiled ELM (FHIRHelpers, QICore, etc.) produced by one batch is
        //   reused by subsequent batches without recompilation. Safe because VersionedIdentifier
        //   includes the library's own namespace URI, so same name+version always means same ELM.
        //   Each batch gets its own fresh EvaluationSettings; only the libraryCache map is injected
        //   as shared so that valueSetCache, modelCache, etc. remain independent per batch.
        val terminologyRepos = mutableMapOf<String?, IRepository>()
        val libraryCaches = mutableMapOf<String?, ConcurrentHashMap<VersionedIdentifier, CompiledLibrary>>()

        val allResults =
            grouped.values.flatMap { batch ->
                val terminologyUri = batch.first().terminologyUri
                val terminologyRepo =
                    terminologyRepos.getOrPut(terminologyUri) {
                        val path = terminologyUri?.let { Paths.get(Uris.parseOrNull(it)!!) }
                        if (path != null) {
                            IgStandardRepository(fhirContext, path)
                        } else {
                            NoOpRepository(fhirContext)
                        }
                    }
                val sharedLibraryCache = libraryCaches.getOrPut(request.optionsPath) { ConcurrentHashMap() }
                val evaluationSettings =
                    buildEvaluationSettings(
                        buildCqlOptions(request.optionsPath),
                        NpmProcessor(igContext),
                    ).withLibraryCache(sharedLibraryCache)
                evaluateBatch(batch, fhirContext, npmProcessor, contentService, terminologyRepo, evaluationSettings, sharedLibraryCache)
            }

        return ExecuteCqlResponse(allResults, emptyList())
    }

    /**
     * Evaluates a batch of [LibraryRequest]s that all refer to the same CQL library.
     * One engine is created for the batch; CQL is compiled on the first call and the
     * [LibraryManager] cache serves all subsequent patients without recompilation.
     *
     * Terminology is loaded once and shared across all patients. Per-patient data is
     * isolated by swapping [DelegatingRepository.current] before each evaluate call.
     * If a `shared/` sibling directory exists alongside the patient UUID directories,
     * its resources are made available as a fallback via [FederatedRepository].
     */
    private fun evaluateBatch(
        batch: List<LibraryRequest>,
        fhirContext: FhirContext,
        npmProcessor: NpmProcessor,
        contentService: ContentService,
        terminologyRepo: IRepository,
        evaluationSettings: EvaluationSettings,
        libraryCache: ConcurrentHashMap<VersionedIdentifier, CompiledLibrary>,
    ): List<LibraryResult> {
        val first = batch.first()
        val batchStart = System.currentTimeMillis()

        // Shared data directory — sibling of patient UUID dirs, e.g. "shared/".
        // Loaded once; individual patient repos fall back to it via FederatedRepository.
        val firstModelPath = first.model?.modelUri?.let { Paths.get(Uris.parseOrNull(it)!!) }
        val sharedDataPath =
            firstModelPath?.parent?.resolve("shared")
                ?.takeIf { Files.isDirectory(it) }
        val sharedDataRepo: IRepository? = sharedDataPath?.let { IgStandardRepository(fhirContext, it) }

        // Mutable data slot — swapped per patient before each engine.evaluate() call.
        val delegatingData = DelegatingRepository(NoOpRepository(fhirContext))
        val compositeRepo = ProxyRepository(delegatingData, delegatingData, terminologyRepo)

        val engineStart = System.currentTimeMillis()
        val engine = Engines.forRepository(compositeRepo, evaluationSettings)
        val setupMs = System.currentTimeMillis() - batchStart
        val engineMs = System.currentTimeMillis() - engineStart
        log.debug(
            "[PERF] ${first.libraryName} batch setup (options+settings+repos+engine): ${setupMs}ms" +
                "  (engine only: ${engineMs}ms)  ${heapStats()}",
        )

        // Register source / model providers once for the entire batch.
        val libraryUri = first.libraryUri?.let { Uris.parseOrNull(it) }
        val libraryKotlinPath = libraryUri?.let { Path(Paths.get(it).toString()) }

        if (libraryUri != null) {
            engine.environment.libraryManager!!.librarySourceLoader.registerProvider(
                ContentServiceSourceProvider(libraryUri, contentService),
            )
            engine.environment.libraryManager!!.modelManager.modelInfoLoader.registerModelInfoProvider(
                ContentServiceModelInfoProvider(libraryUri, contentService),
            )
        } else if (libraryKotlinPath != null) {
            engine.environment.libraryManager!!.librarySourceLoader.registerProvider(
                DefaultLibrarySourceProvider(libraryKotlinPath),
            )
            engine.environment.libraryManager!!.modelManager.modelInfoLoader.registerModelInfoProvider(
                DefaultModelInfoProvider(libraryKotlinPath),
            )
        }

        val identifier = VersionedIdentifier().withId(first.libraryName)
        val results = mutableListOf<LibraryResult>()
        var patientIndex = 0

        for (library in batch) {
            val patientStart = System.currentTimeMillis()
            patientIndex++
            try {
                // Per-patient data repo — isolated to prevent resource ID collisions across patients.
                val modelPath = library.model?.modelUri?.let { Paths.get(Uris.parseOrNull(it)!!) }
                val patientRepo: IRepository =
                    if (modelPath != null) {
                        IgStandardRepository(fhirContext, modelPath)
                    } else {
                        NoOpRepository(fhirContext)
                    }
                val repoCreatedAt = System.currentTimeMillis()

                // Swap in the patient repo (with shared fallback if available).
                delegatingData.current =
                    if (sharedDataRepo != null) {
                        FederatedRepository(patientRepo, sharedDataRepo)
                    } else {
                        patientRepo
                    }

                val coercedParams = coerceParameters(library.parameters)
                val evaluationResults =
                    engine.evaluate {
                        library(identifier)
                        parameters = coercedParams
                        if (library.context != null) {
                            contextParameter = Pair(
                                library.context.contextName,
                                library.context.contextValue as Any,
                            )
                        }
                    }
                val evaluatedAt = System.currentTimeMillis()
                val repoMs = repoCreatedAt - patientStart
                val evalMs = evaluatedAt - repoCreatedAt
                val totalMs = evaluatedAt - patientStart
                log.debug(
                    "[PERF] patient $patientIndex/${batch.size} (${library.context?.contextValue}): " +
                        "repoCreate=${repoMs}ms  evaluate=${evalMs}ms  total=${totalMs}ms  ${heapStats()}",
                )

                val result = evaluationResults.getResultFor(identifier)!!
                val expressions =
                    result.expressionResults.map { (key, value) ->
                        ExpressionResult(key, tempConvert(value.value))
                    }

                // Detect parameters declared in the CQL library that were not supplied via config
                // and therefore fell back to their CQL default value. After engine.evaluate()
                // the resolved value is cached in engine.state.parameters under the key
                // "${libraryId}.${paramName}" (set by ParameterRefEvaluator on first access).
                //
                // We look up the compiled library from the shared cache by name rather than
                // calling resolveLibrary(identifier), because the engine stores the compiled library
                // under its actual versioned identifier (e.g. id="MyLib", version="1.0.0"). An
                // unversioned VersionedIdentifier lookup would miss the cached entry, potentially
                // triggering a recompile that silently fails — causing usedDefaultParameters to be
                // empty for any CQL library that declares a version.
                val usedDefaultParameters: List<DefaultParameterResult> =
                    try {
                        val compiledLib =
                            libraryCache.entries
                                .firstOrNull { it.key.id == identifier.id }
                                ?.value
                        compiledLib
                            ?.library
                            ?.parameters
                            ?.def
                            ?.filter { paramDef ->
                                paramDef.name != null &&
                                    paramDef.default != null &&
                                    !coercedParams.containsKey(paramDef.name)
                            }
                            ?.map { paramDef ->
                                val resolvedValue =
                                    engine.state.parameters["${identifier.id}.${paramDef.name}"]
                                        ?: engine.state.parameters[paramDef.name]
                                DefaultParameterResult(paramDef.name!!, tempConvert(resolvedValue))
                            }
                            ?: emptyList()
                    } catch (e: Exception) {
                        log.debug("Could not inspect default parameters for ${library.libraryName}: ${e.message}")
                        emptyList()
                    }

                results.add(LibraryResult(library.libraryName, expressions, usedDefaultParameters))
            } catch (e: Exception) {
                log.error("Error evaluating library ${library.libraryName} for context ${library.context?.contextValue}", e)
                results.add(
                    LibraryResult(
                        library.libraryName,
                        listOf(ExpressionResult("Error", e.message ?: e.javaClass.simpleName)),
                    ),
                )
            }
        }
        val batchTotalMs = System.currentTimeMillis() - batchStart
        log.debug(
            "[PERF] ${first.libraryName} batch total: ${batchTotalMs}ms  (${batch.size} patients)  ${heapStats()}",
        )

        return results
    }
}
