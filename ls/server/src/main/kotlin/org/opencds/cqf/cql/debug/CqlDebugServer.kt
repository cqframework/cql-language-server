package org.opencds.cqf.cql.debug

import ca.uhn.fhir.context.BaseRuntimeChildDefinition
import ca.uhn.fhir.context.BaseRuntimeElementDefinition
import ca.uhn.fhir.context.FhirContext
import com.google.gson.Gson
import org.cqframework.cql.cql2elm.CqlCompiler
import org.cqframework.cql.gen.cqlParser
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.debug.Breakpoint
import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.ContinueArguments
import org.eclipse.lsp4j.debug.ContinueResponse
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.EvaluateArguments
import org.eclipse.lsp4j.debug.EvaluateResponse
import org.eclipse.lsp4j.debug.ExitedEventArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.NextArguments
import org.eclipse.lsp4j.debug.Scope
import org.eclipse.lsp4j.debug.ScopesArguments
import org.eclipse.lsp4j.debug.ScopesResponse
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsResponse
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.SourceArguments
import org.eclipse.lsp4j.debug.SourceResponse
import org.eclipse.lsp4j.debug.StackFrame
import org.eclipse.lsp4j.debug.StackTraceArguments
import org.eclipse.lsp4j.debug.StackTraceResponse
import org.eclipse.lsp4j.debug.StepInArguments
import org.eclipse.lsp4j.debug.StepOutArguments
import org.eclipse.lsp4j.debug.StoppedEventArguments
import org.eclipse.lsp4j.debug.TerminateArguments
import org.eclipse.lsp4j.debug.TerminatedEventArguments
import org.eclipse.lsp4j.debug.Thread
import org.eclipse.lsp4j.debug.ThreadsResponse
import org.eclipse.lsp4j.debug.Variable
import org.eclipse.lsp4j.debug.VariablesArguments
import org.eclipse.lsp4j.debug.VariablesResponse
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.hl7.elm.r1.Element
import org.hl7.elm.r1.FunctionDef
import org.hl7.elm.r1.Interval
import org.hl7.elm.r1.IntervalTypeSpecifier
import org.hl7.elm.r1.ListTypeSpecifier
import org.hl7.elm.r1.Literal
import org.hl7.elm.r1.NamedTypeSpecifier
import org.hl7.elm.r1.ParameterDef
import org.hl7.elm.r1.Property
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.fhir.instance.model.api.IBase
import org.opencds.cqf.cql.engine.execution.State
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.command.ContextRequest
import org.opencds.cqf.cql.ls.server.command.CqlEvaluator
import org.opencds.cqf.cql.ls.server.command.DetailedExpressionResult
import org.opencds.cqf.cql.ls.server.command.ExecuteCqlRequest
import org.opencds.cqf.cql.ls.server.command.LibraryRequest
import org.opencds.cqf.cql.ls.server.command.ModelRequest
import org.opencds.cqf.cql.ls.server.command.ParameterRequest
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.provider.CursorCategory
import org.opencds.cqf.cql.ls.server.provider.CursorClassifier
import org.opencds.cqf.cql.ls.server.utility.ElmAstLibraryWriter
import org.opencds.cqf.cql.ls.server.visitor.CqlStepPositionCollector
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

open class CqlDebugServer(
    private val compilationManager: CqlCompilationManager,
    private val contentService: ContentService,
    private val igContextManager: IgContextManager,
    private val libraryResolutionManager: LibraryResolutionManager,
) : IDebugProtocolServer, IDebugProtocolClientAware {
    companion object {
        private val log = LoggerFactory.getLogger(CqlDebugServer::class.java)
    }

    // Backing fields shared with varResolver so inline code and helpers use the same maps.
    private val fhirContext: FhirContext = FhirContext.forR4()
    private val varRefs = mutableMapOf<Int, Any>()
    private val varRefTypes = mutableMapOf<Int, String>()
    private var nextVarRef = 1000

    protected val varResolver =
        VariableResolver(
            fhirContext = fhirContext,
            varRefs = varRefs,
            varRefTypes = varRefTypes,
            nextVarRef = nextVarRef,
        )
    protected val breakpointManager: BreakpointManager = BreakpointManager(contentService)
    protected val evaluateHelper: EvaluateHelper = EvaluateHelper(varResolver, compilationManager)

    private val terminatedSent = java.util.concurrent.atomic.AtomicBoolean(false)
    private val exitedSent = java.util.concurrent.atomic.AtomicBoolean(false)
    private var exited: CompletableFuture<Void> = CompletableFuture()

    @Volatile
    private var serverState: ServerState = ServerState.STARTED

    // VS Code sends `launch` immediately after `configurationDone` without waiting for the
    // configurationDone response. lsp4j dispatches both to the thread pool concurrently, so
    // `launch` can arrive before `configurationDone` transitions the state. This future gates
    // `launch` until `configurationDone` has actually completed.
    private val configuredFuture: CompletableFuture<Void> = CompletableFuture()

    protected val client: CompletableFuture<IDebugProtocolClient> = CompletableFuture()

    @Volatile
    protected var streamingHandler: StreamingBreakpointHandler? = null

    protected var streamingExecutor: ExecutorService? = null

    protected var streamingCompletion: CompletableFuture<Unit>? = null

    @Volatile
    protected var streamingLaunchUri: String? = null

    @Volatile
    private var launchCompiler: CqlCompiler? = null

    @Volatile
    private var variableTypeMap: Map<String, String> = emptyMap()

    @Volatile
    protected var launchArgs: DebugLaunchArgs? = null

    fun enableStreaming() {
        streamingHandler = StreamingBreakpointHandler()
    }

    protected var snapshots: List<ExpressionSnapshot> = emptyList()
    protected var subExpressionSnapshots: List<SubExpressionSnapshot> = emptyList()

    @Volatile
    protected var currentIndex: Int = -1
    protected val breakpointLines = mutableSetOf<Int>()

    /** Backing maps — kept on CqlDebugServer for reflection-based test access. */
    private val librarySourceMap = ConcurrentHashMap<String, URI>()
    private val sourceReferenceRegistry = ConcurrentHashMap<Int, VersionedIdentifier>()
    private val nextSourceReference = AtomicInteger(1)
    private val breakpointsByLibrary = ConcurrentHashMap<String, MutableSet<Int>>()

    /** Path-based fallback for breakpoints set before the library is resolved. */
    private val sourcePathBreakpoints = ConcurrentHashMap<String, MutableSet<Int>>()

    private val nextBreakpointId = AtomicInteger(1)
    private val breakpointIdsByPath = ConcurrentHashMap<String, ConcurrentHashMap<Int, Int>>()

    @Volatile
    internal var relevantLibraryIds: Set<String>? = null

    data class ParameterMetadata(
        val name: String,
        val type: String,
        val defaultValue: String?,
    )

    @Volatile
    protected var parameterMetadata: Map<String, List<ParameterMetadata>> = emptyMap()

    @Volatile
    protected var launchParameters: List<ParameterRequestData>? = null

    // >= 100000 reserved for library group refs (below that is the FHIR/Gson band >= 1000)
    private var nextLibraryGroupRef = 100000

    override fun connect(client: IDebugProtocolClient) {
        this.client.complete(client)
    }

    override fun initialize(args: InitializeRequestArguments): CompletableFuture<Capabilities> {
        checkState(ServerState.STARTED)
        val capabilities = Capabilities()
        capabilities.supportsConfigurationDoneRequest = true
        capabilities.supportsEvaluateForHovers = true
        capabilities.supportsTerminateRequest = true

        setState(ServerState.INITIALIZED)
        return CompletableFuture.completedFuture(capabilities)
            .whenCompleteAsync { _, _ -> this.client.join().initialized() }
    }

    override fun configurationDone(args: ConfigurationDoneArguments?): CompletableFuture<Void> {
        checkState(ServerState.INITIALIZED)
        setState(ServerState.CONFIGURED)
        configuredFuture.complete(null)
        return CompletableFuture.completedFuture(null)
    }

    override fun disconnect(args: DisconnectArguments): CompletableFuture<Void> {
        if (getState() == ServerState.STOPPED) return CompletableFuture.completedFuture(null)
        val t0 = System.nanoTime()
        log.debug("disconnect: start [thread={}]", java.lang.Thread.currentThread().name)
        streamingHandler?.release()
        log.debug("disconnect: release() done [+{}ms]", (System.nanoTime() - t0) / 1_000_000)
        streamingExecutor?.shutdownNow()
        streamingExecutor?.awaitTermination(1, TimeUnit.SECONDS)
        log.debug("disconnect: shutdownNow()+awaitTermination done [+{}ms]", (System.nanoTime() - t0) / 1_000_000)
        terminateServer()
        log.debug("disconnect: terminateServer() done [+{}ms]", (System.nanoTime() - t0) / 1_000_000)
        exitServer()
        log.debug("disconnect: exitServer() done [+{}ms]", (System.nanoTime() - t0) / 1_000_000)
        return CompletableFuture.completedFuture(null)
    }

    override fun terminate(args: TerminateArguments): CompletableFuture<Void> {
        return disconnect(DisconnectArguments())
    }

    override fun setExceptionBreakpoints(args: SetExceptionBreakpointsArguments): CompletableFuture<SetExceptionBreakpointsResponse> =
        CompletableFuture.completedFuture(SetExceptionBreakpointsResponse())

    override fun setBreakpoints(args: SetBreakpointsArguments): CompletableFuture<SetBreakpointsResponse> {
        val sourcePath = args.source?.path
        val libId = resolveLibraryIdFromPath(sourcePath) ?: streamingHandler?.primaryLibraryId ?: ""

        val lines = args.breakpoints?.map { it.line }?.toMutableSet() ?: mutableSetOf()
        breakpointsByLibrary[libId] = lines
        streamingHandler?.breakpointsByLibrary?.set(libId, lines)

        // Store by source path as well for resolution before library is entered
        if (sourcePath != null) {
            sourcePathBreakpoints[sourcePath] = lines
        }

        // Update the flat breakpoint set for non-streaming fallback (0-indexed)
        breakpointLines.clear()
        args.breakpoints?.forEach { breakpointLines.add(it.line - 1) }

        // Compute breakpointable lines from the ANTLR parse tree, if available.
        // This allows marking breakpoints on blank/invalid lines as unverified.
        val breakpointableLines: Set<Int>? =
            runCatching {
                val treeUri =
                    resolveLibraryIdFromPath(sourcePath)?.let { librarySourceMap[it] }
                        ?: if (sourcePath != null) Paths.get(sourcePath).toUri() else null
                treeUri?.let { compilationManager.getParseTree(it) }
                    ?.let { CqlStepPositionCollector.collectBreakpointableLines(it) }
            }.getOrNull()

        val bps =
            args.breakpoints?.map { bp ->
                val id = nextBreakpointId.getAndIncrement()
                if (sourcePath != null) {
                    breakpointIdsByPath
                        .computeIfAbsent(sourcePath) { ConcurrentHashMap() }
                        .put(bp.line, id)
                }
                val lineIsBreakpointable = breakpointableLines?.contains(bp.line) ?: true
                Breakpoint().also {
                    it.id = id
                    it.isVerified = lineIsBreakpointable && (relevantLibraryIds == null || isRelevantSourcePath(sourcePath))
                    it.line = bp.line
                }
            }?.toTypedArray() ?: emptyArray()
        return CompletableFuture.completedFuture(SetBreakpointsResponse().also { it.breakpoints = bps })
    }

    private fun resolveLibraryIdFromPath(path: String?): String? =
        breakpointManager.resolveLibraryIdFromPath(path, librarySourceMap)

    private fun isRelevantSourcePath(sourcePath: String?): Boolean =
        breakpointManager.isRelevantSourcePath(sourcePath, librarySourceMap, relevantLibraryIds)

    internal fun collectTransitiveIncludes(
        primaryId: String,
        compiler: CqlCompiler?,
    ): Set<String> = breakpointManager.collectTransitiveIncludes(primaryId, compiler, streamingLaunchUri, librarySourceMap)

    private fun updateBreakpointVerification() {
        breakpointManager.updateBreakpointVerification(client.join(), breakpointIdsByPath, librarySourceMap, relevantLibraryIds)
    }

    private fun updateBreakpointLineVerification(parseTree: cqlParser.LibraryContext) {
        breakpointManager.updateBreakpointLineVerification(parseTree, client.join(), breakpointIdsByPath)
    }

    private fun extractParameterMetadata(libraryUri: URI): Map<String, List<ParameterMetadata>> {
        val compiler = compilationManager.compile(libraryUri)
        val metadata = mutableMapOf<String, MutableList<ParameterMetadata>>()

        val libraryName = compiler?.compiledLibrary?.library?.identifier?.id ?: "Unknown"

        compiler?.compiledLibrary?.library?.parameters?.def?.forEach { paramDef ->
            val name = paramDef.name ?: return@forEach
            val typeName = extractTypeName(paramDef)
            val defaultValueStr = extractDefaultValue(paramDef)

            metadata.getOrPut(libraryName) { mutableListOf() }.add(
                ParameterMetadata(
                    name = name,
                    type = typeName,
                    defaultValue = defaultValueStr,
                ),
            )
        }

        return metadata
    }

    private fun extractTypeName(paramDef: ParameterDef): String {
        paramDef.parameterTypeSpecifier?.let { typeSpec ->
            return serializeTypeSpecifier(typeSpec)
        }

        paramDef.parameterType?.let { qname ->
            return qname.localPart ?: "Unknown"
        }

        return "Unknown"
    }

    private fun serializeTypeSpecifier(typeSpec: Any?): String {
        return when (typeSpec) {
            is NamedTypeSpecifier -> {
                typeSpec.name?.localPart ?: "Unknown"
            }
            is IntervalTypeSpecifier -> {
                val pointType = serializeTypeSpecifier(typeSpec.pointType)
                "Interval<$pointType>"
            }
            is ListTypeSpecifier -> {
                val elementType = serializeTypeSpecifier(typeSpec.elementType)
                "List<$elementType>"
            }
            else -> typeSpec?.javaClass?.simpleName ?: "Unknown"
        }
    }

    private fun extractDefaultValue(paramDef: ParameterDef): String? {
        val defaultExpr = paramDef.default ?: return null

        return when (defaultExpr) {
            is Literal -> {
                defaultExpr.value ?: "null"
            }
            is Interval -> {
                val low = (defaultExpr.low as? Literal)?.value
                val high = (defaultExpr.high as? Literal)?.value
                "Interval[$low, $high]"
            }
            else -> {
                "(${defaultExpr.javaClass.simpleName})"
            }
        }
    }

    override fun launch(args: Map<String, Any>): CompletableFuture<Void> {
        return configuredFuture.thenCompose {
            checkState(ServerState.CONFIGURED)
            setState(ServerState.RUNNING)
            CompletableFuture.runAsync {
                try {
                    val parsedArgs = Gson().fromJson(Gson().toJson(args), DebugLaunchArgs::class.java)
                    this.launchArgs = parsedArgs
                    if (streamingHandler != null) {
                        executeLaunchStreaming(parsedArgs)
                    } else {
                        executeLaunch(parsedArgs)
                    }
                } catch (e: Exception) {
                    log.error("CQL debug launch failed", e)
                    terminateServer()
                    exitServer(1)
                }
            }
        }
    }

    protected open fun executeLaunch(args: DebugLaunchArgs) {
        val libraryUri = URI.create(args.libraryUri)

        parameterMetadata = extractParameterMetadata(libraryUri)
        launchParameters = args.parameters

        val request = buildExecuteCqlRequest(args)
        val detailedResult = CqlEvaluator.evaluateDetailed(request, contentService, igContextManager, libraryResolutionManager)
        val expressions = detailedResult.response.results.firstOrNull()?.expressions ?: emptyList()

        val compiler = compilationManager.compile(libraryUri)
        launchCompiler = compiler
        variableTypeMap = buildVariableTypeMap(compiler)
        val locatorMap: Map<String, String?> =
            compiler?.compiledLibrary?.library
                ?.statements?.def
                ?.filterNot { it is FunctionDef }
                ?.filter { it.name != null }
                ?.associate { it.name!! to it.locator }
                ?: emptyMap()

        val orderIndex = detailedResult.defineOrder.withIndex().associate { (i, name) -> name to i }
        // Sort by dependency-first evaluation order (from trace), not source order.
        // Defines not present in the trace sort to the end.
        snapshots =
            expressions.mapNotNull { expr ->
                parseLocator(locatorMap[expr.name] ?: return@mapNotNull null, expr.name, expr.value, args.libraryUri)
            }.sortedBy { orderIndex[it.name] ?: Int.MAX_VALUE }

        subExpressionSnapshots =
            detailedResult.subExpressions.mapNotNull { detail ->
                parseSubExpressionLocator(detail)
            }

        currentIndex = -1
        stepToNext("entry")
    }

    protected open fun executeLaunchStreaming(args: DebugLaunchArgs) {
        val handler = streamingHandler ?: error("Streaming handler not initialized")

        handler.reset()

        handler.onPauseCallback = { elm, state ->
            val paramTypes =
                parameterMetadata.mapValues { (_, metadata) ->
                    metadata.associate { it.name to it.type }
                }
            handler.runtimeRegistry.loadParameters(state, paramTypes)
            val locator = elm.locator
            if (locator != null && StreamingBreakpointHandler.parseLine(locator) != null) {
                this.client.join().stopped(
                    StoppedEventArguments().also {
                        it.reason = if (handler.getStepMode() == StreamingBreakpointHandler.StepMode.CONTINUE) "breakpoint" else "step"
                        it.threadId = 1
                        it.allThreadsStopped = true
                    },
                )
            }
        }

        handler.onLibraryEnteredCallback = migration@{ libId, libraryIdentifier ->
            if (!librarySourceMap.containsKey(libId)) {
                try {
                    val compiler = launchCompiler
                    val allDefs = compiler?.compiledLibrary?.library?.includes?.def ?: emptyList()
                    val identifier =
                        allDefs.firstOrNull { it.path == libId || it.localIdentifier == libId }
                            ?.let { def ->
                                VersionedIdentifier().also { vi ->
                                    vi.id = def.path
                                    vi.version = def.version
                                }
                            } ?: libraryIdentifier
                    if (identifier != null) {
                        val uris = contentService.locate(URI.create(streamingLaunchUri!!), identifier)
                        val uri = uris.firstOrNull()
                        if (uri != null) {
                            librarySourceMap[libId] = uri
                        } else {
                            val ref = nextSourceReference.getAndIncrement()
                            sourceReferenceRegistry[ref] = identifier
                            librarySourceMap[libId] = URI.create("cql-source://$ref")
                        }
                        loadStepLinesForLibrary(libId, identifier)
                        loadVariableTypesForLibrary(libId)
                    }
                } catch (e: Exception) {
                    log.debug("onLibraryEnteredCallback: failed to resolve library $libId", e)
                }
            }

            // Migrate path-keyed breakpoints set before this library's ID was known.
            // This runs OUTSIDE the !containsKey guard because collectTransitiveIncludes
            // pre-populates librarySourceMap for direct includes before execution starts,
            // which would otherwise prevent migration from ever running.
            // Use Path.equals() for the lookup — on Windows this is case-insensitive,
            // which handles drive-letter case differences (File.toURI() → uppercase C:
            // vs VS Code source.path → lowercase c:).
            try {
                val uri = librarySourceMap[libId] ?: return@migration
                val uriPath = Paths.get(uri)
                val pending =
                    sourcePathBreakpoints.entries
                        .firstOrNull { (key, _) ->
                            try {
                                Paths.get(key) == uriPath
                            } catch (_: Exception) {
                                false
                            }
                        }?.value ?: return@migration
                breakpointsByLibrary[libId] = pending
                handler.breakpointsByLibrary[libId] = pending
            } catch (_: Exception) {
            }
        }

        streamingLaunchUri = args.libraryUri

        val libraryUri = URI.create(args.libraryUri)
        val parseTree = compilationManager.getParseTree(libraryUri)
        if (parseTree != null) {
            handler.applyCqlStepLineFilter(CqlStepPositionCollector.collect(parseTree))
        }

        val compiler = compilationManager.compile(libraryUri)
        handler.primaryLibraryId = compiler?.library?.identifier?.id
        launchCompiler = compiler
        variableTypeMap = buildVariableTypeMap(compiler)
        handler.variableTypeMap = variableTypeMap

        // Re-populate handler's per-library breakpoints from server's maps
        // (reset() cleared the handler's copy, but this.breakpointsByLibrary survived)
        streamingHandler?.breakpointsByLibrary?.putAll(breakpointsByLibrary)

        // Populate primary library in the per-library maps
        handler.primaryLibraryId?.let { id ->
            handler.cqlStepLinesByLibrary[id] = handler.cqlStepLines ?: emptySet()
        }

        // Migrate breakpoints that were set before the primary library ID was known.
        // Pre-launch setBreakpoints calls store under sourcePathBreakpoints (keyed by
        // the file path) because resolveLibraryIdFromPath returns null — librarySourceMap
        // hasn't been populated yet.  Use Path.equals() for case-insensitive comparison
        // on Windows (File.toURI() uppercases drive letters; VS Code sends lowercase).
        val primaryId = handler.primaryLibraryId
        if (primaryId != null) {
            val primaryPath = Paths.get(libraryUri)
            val pendingPrimary =
                sourcePathBreakpoints.entries
                    .firstOrNull { (key, _) ->
                        try {
                            Paths.get(key) == primaryPath
                        } catch (_: Exception) {
                            false
                        }
                    }?.value
            if (pendingPrimary != null) {
                breakpointsByLibrary[primaryId] = pendingPrimary
                // Write directly to the handler — putAll already ran above, so the
                // handler won't pick up this server-map update via putAll.
                streamingHandler?.breakpointsByLibrary?.set(primaryId, pendingPrimary)
            }

            // Register primary library in source map so updateBreakpointVerification
            // can resolve its path via resolveLibraryIdFromPath (was missing, causing
            // all primary-library breakpoints to be marked unverified).
            librarySourceMap[primaryId] = libraryUri

            // Compute transitive includes and update breakpoint verification
            val transitiveIncludes = collectTransitiveIncludes(primaryId, compiler)
            relevantLibraryIds = transitiveIncludes
            updateBreakpointVerification()

            // After library resolution, correct verification for breakpoints on
            // lines that don't correspond to evaluatable CQL expressions.
            val parseTreeAfterLaunch = compilationManager.getParseTree(libraryUri)
            if (parseTreeAfterLaunch != null) {
                updateBreakpointLineVerification(parseTreeAfterLaunch)
            }
        }

        handler.stepGranularity =
            if (args.stepGranularity?.equals("ast", ignoreCase = true) == true) {
                StreamingBreakpointHandler.StepGranularity.AST
            } else {
                StreamingBreakpointHandler.StepGranularity.CQL
            }

        parameterMetadata = extractParameterMetadata(libraryUri)
        launchParameters = args.parameters

        val request = buildExecuteCqlRequest(args)

        streamingExecutor = Executors.newSingleThreadExecutor()

        streamingCompletion =
            CqlEvaluator.evaluateStreaming(
                request,
                contentService,
                igContextManager,
                libraryResolutionManager,
                handler,
                streamingExecutor!!,
            )

        streamingCompletion!!.whenComplete { _, error ->
            if (error != null) {
                log.debug("streamingCompletion: error/cancellation branch [thread={}]", java.lang.Thread.currentThread().name)
                terminateServer()
                exitServer(1)
            } else {
                log.debug("streamingCompletion: normal completion branch [thread={}]", java.lang.Thread.currentThread().name)
                terminateServer()
                exitServer()
            }
        }
    }

    private fun loadStepLinesForLibrary(
        libId: String,
        identifier: VersionedIdentifier?,
    ) {
        if (identifier == null) return
        try {
            val uris = contentService.locate(URI.create(streamingLaunchUri ?: return), identifier)
            val uri = uris.firstOrNull() ?: return
            val parseTree = compilationManager.getParseTree(uri) ?: return
            val lines = CqlStepPositionCollector.collect(parseTree)
            streamingHandler?.cqlStepLinesByLibrary?.set(libId, lines)
        } catch (e: Exception) {
            log.debug("loadStepLinesForLibrary: failed for $libId", e)
        }
    }

    private fun loadVariableTypesForLibrary(libId: String) {
        val sourceUri = librarySourceMap[libId] ?: return
        try {
            val compiler = compilationManager.compile(sourceUri) ?: return
            val additions = buildVariableTypeMap(compiler)
            val qualifiedAdditions = additions.mapKeys { (name, _) -> "$libId.$name" }
            val updated = (streamingHandler?.variableTypeMap ?: emptyMap()) + qualifiedAdditions
            streamingHandler?.variableTypeMap = updated
            variableTypeMap = updated
        } catch (e: Exception) {
            log.debug("loadVariableTypesForLibrary: failed for $libId", e)
        }
    }

    private fun buildExecuteCqlRequest(args: DebugLaunchArgs): ExecuteCqlRequest {
        return ExecuteCqlRequest(
            fhirVersion = args.fhirVersion,
            rootDir = args.rootDir,
            optionsPath = args.optionsPath,
            libraries =
                listOf(
                    LibraryRequest(
                        libraryName = args.libraryName,
                        libraryUri = args.libraryUri,
                        libraryVersion = null,
                        terminologyUri = args.terminologyUri,
                        model = args.testCaseUri?.let { ModelRequest("FHIR", it) },
                        context = args.testCaseName?.let { ContextRequest("Patient", it) },
                        parameters =
                            args.parameters?.map { p ->
                                ParameterRequest(
                                    parameterName = p.parameterName,
                                    parameterType = p.parameterType,
                                    parameterValue = p.parameterValue,
                                )
                            } ?: emptyList(),
                    ),
                ),
        )
    }

    private fun parseLocator(
        locator: String,
        name: String,
        value: String,
        uri: String,
    ): ExpressionSnapshot? {
        val (start, end) = locator.split("-").takeIf { it.size == 2 } ?: return null
        val (sl, sc) = start.split(":").takeIf { it.size == 2 }?.map { it.toIntOrNull() } ?: return null
        val (el, ec) = end.split(":").takeIf { it.size == 2 }?.map { it.toIntOrNull() } ?: return null
        if (sl == null || sc == null || el == null || ec == null) return null
        return ExpressionSnapshot(name, value, uri, sl - 1, sc - 1, el - 1, ec)
    }

    protected fun stepToNext(reason: String = "step") {
        currentIndex++
        if (currentIndex >= snapshots.size) {
            terminateServer()
            exitServer()
            return
        }
        this.client.join().stopped(
            StoppedEventArguments().also {
                it.reason = reason
                it.threadId = 1
                it.allThreadsStopped = true
            },
        )
    }

    override fun threads(): CompletableFuture<ThreadsResponse> =
        CompletableFuture.completedFuture(
            ThreadsResponse().also {
                it.threads =
                    arrayOf(
                        Thread().also { t ->
                            t.id = 1
                            t.name = "CQL"
                        },
                    )
            },
        )

    override fun stackTrace(args: StackTraceArguments): CompletableFuture<StackTraceResponse> {
        val handler = streamingHandler
        if (handler != null) {
            val frames =
                when (handler.stepGranularity) {
                    StreamingBreakpointHandler.StepGranularity.CQL -> buildCqlStackFrames(handler)
                    StreamingBreakpointHandler.StepGranularity.AST -> buildCqlStackFrames(handler)
                }
            return CompletableFuture.completedFuture(
                StackTraceResponse().also {
                    it.stackFrames = frames.toTypedArray()
                    it.totalFrames = frames.size
                },
            )
        }
        val snap = snapshots.getOrNull(currentIndex)
        val frame =
            StackFrame().also { f ->
                f.id = currentIndex.coerceAtLeast(0)
                f.name = snap?.name ?: "(none)"
                f.line = (snap?.startLine ?: 0) + 1
                f.column = (snap?.startChar ?: 0) + 1
                f.endLine = (snap?.endLine ?: 0) + 1
                f.endColumn = (snap?.endChar ?: 0) + 1 // +1 because TrackBack end is inclusive but DAP endColumn is exclusive
                f.source =
                    snap?.let {
                        Source().also { s -> s.path = Paths.get(URI.create(it.sourceUri)).toString() }
                    }
            }
        return CompletableFuture.completedFuture(
            StackTraceResponse().also {
                it.stackFrames = arrayOf(frame)
                it.totalFrames = 1
            },
        )
    }

    override fun scopes(args: ScopesArguments): CompletableFuture<ScopesResponse> {
        val handler = streamingHandler
        val state = handler?.lastPausedState

        val scopes = mutableListOf<Scope>()

        val hasParameters = parameterMetadata.isNotEmpty() || (state?.parameters?.isNotEmpty() == true)
        val hasTestCase = launchArgs?.testCaseUri != null || launchArgs?.testCaseName != null

        if (handler != null) {
            if (hasParameters) {
                scopes.add(
                    Scope().also { s ->
                        s.name = "Parameters"
                        s.variablesReference = 2
                        s.isExpensive = false
                    },
                )
            }
            scopes.add(
                Scope().also { s ->
                    s.name = "Locals"
                    s.variablesReference = 1
                    s.isExpensive = false
                },
            )
            scopes.add(
                Scope().also { s ->
                    s.name = "Resolved Defines"
                    s.variablesReference = 3
                    s.isExpensive = false
                },
            )
            if (hasTestCase) {
                scopes.add(
                    Scope().also { s ->
                        s.name = "Test Case"
                        s.variablesReference = 4
                        s.isExpensive = false
                    },
                )
            }
        } else {
            scopes.add(
                Scope().also { s ->
                    s.name = "Expressions"
                    s.variablesReference = 1
                    s.isExpensive = false
                },
            )
            if (hasTestCase) {
                scopes.add(
                    Scope().also { s ->
                        s.name = "Test Case"
                        s.variablesReference = 4
                        s.isExpensive = false
                    },
                )
            }
        }

        return CompletableFuture.completedFuture(
            ScopesResponse().also { it.scopes = scopes.toTypedArray() },
        )
    }

    override fun variables(args: VariablesArguments): CompletableFuture<VariablesResponse> {
        val handler = streamingHandler
        val vars = mutableListOf<Variable>()

        if (handler != null) {
            val state = handler.lastPausedState

            // variablesReference == 2 is the Parameters scope container
            if (args.variablesReference == 2) {
                // Reset library group refs for consistent tree rebuilding
                nextLibraryGroupRef = 100000
                libraryRefToName.clear()

                val groups = handler.runtimeRegistry.getParametersByLibrary()
                if (groups.isNotEmpty()) {
                    return CompletableFuture.completedFuture(
                        VariablesResponse().also { it.variables = buildLibraryGroupVariables(groups).toTypedArray() },
                    )
                } else {
                    // No parameters loaded yet - show metadata grouped by library
                    val metadataGroups = parameterMetadata.mapValues { (_, metadata) -> metadata.map { it.name to null } }
                    return CompletableFuture.completedFuture(
                        VariablesResponse().also { it.variables = buildMetadataLibraryGroupVariables(metadataGroups).toTypedArray() },
                    )
                }
            }

            // Library group expansion (positive refs >= 100000)
            if (args.variablesReference >= 100000) {
                val libraryName = getLibraryNameForRef(args.variablesReference)
                if (libraryName != null) {
                    val gson = Gson()
                    val libParams = handler.runtimeRegistry.getParametersByLibrary()[libraryName]
                    if (libParams != null) {
                        for (param in libParams.sortedBy { it.name }) {
                            val paramType =
                                param.type
                                    ?: findParameterMetadata(libraryName, param.name)?.type
                            vars.add(
                                Variable().also { v ->
                                    v.name = param.name
                                    v.value = formatVariableValue(param.value, gson)
                                    v.type = paramType
                                    v.variablesReference = registerIfExpandable(param.value)
                                },
                            )
                        }
                    } else {
                        // No runtime values yet - show metadata defaults
                        parameterMetadata[libraryName]?.sortedBy { it.name }?.forEach { metadata ->
                            vars.add(
                                Variable().also { v ->
                                    v.name = metadata.name
                                    v.value = metadata.defaultValue ?: "(no default)"
                                    v.type = metadata.type
                                    v.variablesReference = 0
                                },
                            )
                        }
                    }
                }
                return CompletableFuture.completedFuture(
                    VariablesResponse().also { it.variables = vars.toTypedArray() },
                )
            }

            if (args.variablesReference >= 1000) {
                val value = varRefs[args.variablesReference]
                if (value != null) {
                    vars.addAll(childrenOf(value, varRefTypes[args.variablesReference]))
                }
                return CompletableFuture.completedFuture(
                    VariablesResponse().also { it.variables = vars.toTypedArray() },
                )
            }

            // Test Case scope
            if (args.variablesReference == 4) {
                val testCaseList = buildTestCaseVariables()
                return CompletableFuture.completedFuture(
                    VariablesResponse().also { it.variables = testCaseList.toTypedArray() },
                )
            }

            if (state != null) {
                val gson = Gson()
                val registry = handler.runtimeRegistry

                if (args.variablesReference == 1) {
                    varResolver.resetVarRefs()
                    val frameLibId = resolveFrameLibraryId()
                    for (sv in registry.getStackVariables().sortedBy { it.name }) {
                        val svType = variableTypeMap["$frameLibId.${sv.name}"] ?: variableTypeMap[sv.name]
                        vars.add(
                            Variable().also {
                                it.name = sv.name
                                it.value = formatVariableValue(sv.value, gson)
                                it.type = svType
                                it.variablesReference = registerIfExpandable(sv.value, svType)
                            },
                        )
                    }
                    for (cr in registry.getContextResources().sortedBy { it.name }) {
                        val crType = variableTypeMap["$frameLibId.${cr.name}"] ?: variableTypeMap[cr.name]
                        vars.add(
                            Variable().also {
                                it.name = cr.name
                                it.value = formatVariableValue(cr.value, gson)
                                it.type = crType
                                it.variablesReference = registerIfExpandable(cr.value, crType)
                            },
                        )
                    }
                }
                if (args.variablesReference == 3) {
                    for (d in registry.getDefines().sortedBy { it.name }) {
                        val dType =
                            if (d.libraryName != null) {
                                variableTypeMap["${d.libraryName}.${d.name}"] ?: variableTypeMap[d.name]
                            } else {
                                variableTypeMap[d.name]
                            }
                        vars.add(
                            Variable().also {
                                it.name = d.name
                                it.value = formatVariableValue(d.value, gson)
                                it.type = dType
                                it.variablesReference = registerIfExpandable(d.value, dType)
                            },
                        )
                    }
                }
            }
            return CompletableFuture.completedFuture(
                VariablesResponse().also { it.variables = vars.toTypedArray() },
            )
        }

        // Non-streaming mode: variablesReference == 2 shows grouped parameter metadata
        if (args.variablesReference == 2) {
            // Reset library group refs for consistent tree rebuilding
            nextLibraryGroupRef = 100000
            libraryRefToName.clear()

            val groups = parameterMetadata.mapValues { (_, metadata) -> metadata.map { it.name to null } }
            return CompletableFuture.completedFuture(
                VariablesResponse().also { it.variables = buildMetadataLibraryGroupVariables(groups).toTypedArray() },
            )
        }

        // Library group expansion in non-streaming mode (positive refs >= 100000)
        if (args.variablesReference >= 100000) {
            val libraryName = getLibraryNameForRef(args.variablesReference)
            if (libraryName != null) {
                parameterMetadata[libraryName]?.sortedBy { it.name }?.forEach { metadata ->
                    vars.add(
                        Variable().also { v ->
                            v.name = metadata.name
                            v.value = metadata.defaultValue ?: "(no default)"
                            v.type = metadata.type
                            v.variablesReference = 0
                        },
                    )
                }
            }
            return CompletableFuture.completedFuture(
                VariablesResponse().also { it.variables = vars.toTypedArray() },
            )
        }

        // Non-streaming mode: Test Case scope
        if (args.variablesReference == 4) {
            val testCaseList = buildTestCaseVariables()
            return CompletableFuture.completedFuture(
                VariablesResponse().also { it.variables = testCaseList.toTypedArray() },
            )
        }

        val expressionVars =
            snapshots.take(currentIndex + 1).map { snap ->
                Variable().also {
                    it.name = snap.name
                    it.value = snap.value
                    it.type = variableTypeMap[snap.name]
                    it.evaluateName = snap.name
                    it.variablesReference = 0
                }
            }.toTypedArray()
        return CompletableFuture.completedFuture(
            VariablesResponse().also { it.variables = expressionVars },
        )
    }

    private fun splitParameterName(fullName: String): Pair<String, String> {
        val dotIndex = fullName.indexOf('.')
        return if (dotIndex > 0) {
            fullName.substring(0, dotIndex) to fullName.substring(dotIndex + 1)
        } else {
            "(Global)" to fullName
        }
    }

    private fun buildLibraryGroupVariables(groups: Map<String, List<RuntimeValue>>): List<Variable> {
        return groups.toSortedMap().map { (library, params) ->
            val ref = nextLibraryGroupRef++
            libraryRefToName["ref_$ref"] = library
            Variable().also { v ->
                v.name = library
                v.value = "${params.size} parameter(s)"
                v.variablesReference = ref
                v.type = null
            }
        }
    }

    private fun buildMetadataLibraryGroupVariables(groups: Map<String, List<Pair<String, Any?>>>): List<Variable> {
        return groups.toSortedMap().map { (library, _) ->
            val ref = nextLibraryGroupRef++
            libraryRefToName["ref_$ref"] = library
            val paramCount = groups[library]?.size ?: 0
            Variable().also { v ->
                v.name = library
                v.value = "$paramCount parameter(s)"
                v.variablesReference = ref
                v.type = null
            }
        }
    }

    private fun getLibraryNameForRef(ref: Int): String? {
        // Reconstruct library name from ref by looking up in parameterMetadata keys
        // We use a simple approach: store ref->libraryName mapping
        val key = "ref_$ref"
        return libraryRefToName[key]
    }

    private val libraryRefToName = mutableMapOf<String, String>()

    private fun findParameterMetadata(
        libraryName: String,
        paramName: String,
    ): ParameterMetadata? =
        evaluateHelper.findParameterMetadata(libraryName, paramName, parameterMetadata)

    private fun findLaunchParameterType(paramName: String): String? {
        return launchParameters?.find { it.parameterName == paramName }?.parameterType
    }

    override fun evaluate(args: EvaluateArguments): CompletableFuture<EvaluateResponse> {
        val handler = streamingHandler
        if (handler != null) {
            return CompletableFuture.supplyAsync {
                val state = handler.lastPausedState
                if (state != null) {
                    val gson = Gson()
                    val registry = handler.runtimeRegistry

                    // Unified registry lookup (stack vars → defines → context resources → parameters)
                    val registryResult = registry.find(args.expression)
                    if (registryResult != null) {
                        return@supplyAsync EvaluateResponse().also {
                            it.result = formatVariableValue(registryResult.value, gson)
                            it.variablesReference = registerIfExpandable(registryResult.value)
                        }
                    }

                    // Search engine cache for expression results
                    // In streaming mode, the current library identifier might not be in the state stack yet.
                    // We can attempt to resolve the identifier from the paused ELM element.
                    val libId =
                        state.getCurrentLibrary()?.identifier
                            ?: streamingLaunchUri?.let { uriStr ->
                                try {
                                    val uri = java.net.URI.create(uriStr)
                                    compilationManager.compile(uri)?.compiledLibrary?.library?.identifier
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            ?: handler.lastPausedElm?.locator?.let { _ ->
                                org.hl7.elm.r1.VersionedIdentifier().also { it.id = "TestLib" }
                            }

                    if (libId != null) {
                        state.cache.setExpressionCaching(true)
                        val cachedResult = state.cache.getCachedExpression(libId, args.expression)
                        if (cachedResult != null) {
                            return@supplyAsync EvaluateResponse().also {
                                it.result = formatVariableValue(cachedResult.value, gson)
                                it.variablesReference = registerIfExpandable(cachedResult.value)
                            }
                        }
                    }

                    // Handle @line:col position-based hover
                    if (args.expression.startsWith("@")) {
                        val pos = args.expression.removePrefix("@")
                        val parts = pos.split(":")
                        if (parts.size == 2) {
                            val line = parts[0].toIntOrNull()
                            val col = parts[1].toIntOrNull()
                            if (line != null && col != null) {
                                val parseTree = compilationManager.getParseTree(URI.create(streamingLaunchUri ?: ""))
                                if (parseTree != null && state != null) {
                                    val hoverPos = Position(line, col)
                                    val category = CursorClassifier.classify(parseTree, hoverPos)
                                    val classifiedResult = resolveFromCursorCategory(category, state, handler, gson)
                                    if (classifiedResult != null) {
                                        return@supplyAsync classifiedResult
                                    }
                                }
                                val value = handler.findValueAtPosition(line, col)
                                if (value != null) {
                                    return@supplyAsync EvaluateResponse().also {
                                        it.result = formatVariableValue(value, gson)
                                        it.variablesReference = registerIfExpandable(value)
                                    }
                                }
                                // Check if paused on a Property element - try to resolve its value
                                val pausedElm = handler.lastPausedElm
                                if (pausedElm is Property && state != null) {
                                    val propertyResult = resolvePropertyValue(pausedElm, state, gson)
                                    if (propertyResult != null) {
                                        return@supplyAsync EvaluateResponse().also {
                                            it.result = propertyResult.displayString
                                            it.variablesReference = registerIfExpandable(propertyResult.expandableValue)
                                        }
                                    }
                                }
                                // No value yet (paused expression not evaluated), show name
                                val name = handler.getPausedExpressionName()
                                if (name != null) {
                                    return@supplyAsync EvaluateResponse().also {
                                        it.result = "evaluating: $name"
                                        it.variablesReference = 0
                                    }
                                }
                            }
                        }
                    }
                }
                // VarRefs tree fallback for expanded FHIR/list child variables (e.g. name[0], given[0])
                val varRefResult = findInVarRefs(args.expression)
                if (varRefResult != null) return@supplyAsync varRefResult
                notAvailable()
            }
        }
        return CompletableFuture.supplyAsync {
            when (args.context) {
                "hover" -> handleHoverEvaluate(args.expression, args.frameId)
                else -> lookupByName(args.expression, args.frameId)
            }
        }
    }

    private fun lookupByName(
        expression: String,
        frameId: Int?,
    ): EvaluateResponse =
        evaluateHelper.lookupByName(expression, frameId, snapshots, currentIndex)

    private fun handleHoverEvaluate(
        expression: String,
        frameId: Int?,
    ): EvaluateResponse =
        evaluateHelper.handleHoverEvaluate(expression, frameId, snapshots, subExpressionSnapshots)

    private fun nameMatches(
        snapshotName: String,
        expression: String,
    ): Boolean =
        evaluateHelper.nameMatches(snapshotName, expression)

    private fun parseHoverPosition(expression: String): Pair<Int, Int>? =
        evaluateHelper.parseHoverPosition(expression)

    private fun extractExpressionName(elm: Element?): String? =
        varResolver.extractExpressionName(elm)

    private fun parseLocatorLines(locator: String?): LocatorBounds =
        varResolver.parseLocatorLines(locator)

    private fun formatVariableValue(
        value: Any?,
        gson: Gson,
    ): String =
        varResolver.formatVariableValue(value, gson)

    private fun formatPropertyValue(
        value: Any?,
        gson: Gson,
    ): String =
        varResolver.formatPropertyValue(value, gson)

    private fun isExpandable(value: Any?): Boolean =
        varResolver.isExpandable(value)

    private fun registerIfExpandable(
        value: Any?,
        typeName: String? = null,
    ): Int = varResolver.registerIfExpandable(value, typeName)

    private fun childrenOf(
        value: Any,
        typeName: String? = null,
    ): List<Variable> = varResolver.childrenOf(value, typeName, launchCompiler)

    private fun findInVarRefs(name: String): EvaluateResponse? =
        varResolver.findInVarRefs(name)

    private fun resolvePropertyValue(
        property: Property,
        state: org.opencds.cqf.cql.engine.execution.State,
        gson: Gson,
    ): PropertyResult? =
        evaluateHelper.resolvePropertyValue(property, streamingHandler!!, gson)?.let {
            PropertyResult(it.first, it.second)
        }

    private fun resolveFromCursorCategory(
        category: CursorCategory,
        state: State,
        handler: StreamingBreakpointHandler,
        gson: Gson,
    ): EvaluateResponse? = evaluateHelper.resolveFromCursorCategory(category, state, handler, gson)

    private data class PropertyResult(val displayString: String, val expandableValue: Any?)

    private fun resolvePropertyFromAlias(
        aliasName: String,
        propertyName: String,
        state: State,
        handler: StreamingBreakpointHandler,
        gson: Gson,
    ): PropertyResult? =
        evaluateHelper.resolvePropertyFromAlias(aliasName, propertyName, handler, gson)?.let {
            PropertyResult(it.first, it.second)
        }

    private fun extractPropertyValue(
        resource: IBase,
        propertyName: String,
    ): Any? =
        varResolver.extractPropertyValue(resource, propertyName)

    private fun getResourceId(resource: IBase): String =
        varResolver.getResourceId(resource)

    private fun getFhirContextForVersion(version: String?): FhirContext =
        varResolver.getFhirContextForVersion(version)

    private fun buildTestCaseVariables(): List<Variable> =
        varResolver.buildTestCaseVariables(launchArgs?.testCaseUri, launchArgs?.fhirVersion)

    private fun formatPeriodAsInterval(period: org.hl7.fhir.r4.model.Period): String =
        varResolver.formatPeriodAsInterval(period)

    private fun notAvailable(): EvaluateResponse = varResolver.notAvailable()

    /**
     * Parses a locator from a [DetailedExpressionResult] (1-indexed TrackBack format) into
     * a [SubExpressionSnapshot] with 0-indexed LSP line/char bounds.
     */
    private fun parseSubExpressionLocator(detail: DetailedExpressionResult): SubExpressionSnapshot? {
        val locator = detail.locator
        val (start, end) = locator.split("-").takeIf { it.size == 2 } ?: return null
        val (sl, sc) = start.split(":").takeIf { it.size == 2 }?.map { it.toIntOrNull() } ?: return null
        val (el, ec) = end.split(":").takeIf { it.size == 2 }?.map { it.toIntOrNull() } ?: return null
        if (sl == null || sc == null || el == null || ec == null) return null
        return SubExpressionSnapshot(
            value = detail.value,
            parentDefine = detail.parent ?: return null,
            startLine = sl - 1,
            startChar = sc - 1,
            endLine = el - 1,
            endChar = ec,
        )
    }

    override fun next(args: NextArguments): CompletableFuture<Void> {
        val handler = streamingHandler
        if (handler != null) {
            handler.prepareStepOver(handler.lastPausedState?.stack?.size ?: 0)
            CompletableFuture.runAsync { handler.resumeFromPause() }
            return CompletableFuture.completedFuture(null)
        }
        return CompletableFuture.runAsync { stepToNext() }
    }

    override fun stepIn(args: StepInArguments): CompletableFuture<Void> {
        val handler = streamingHandler
        if (handler != null) {
            handler.prepareStepIn()
            CompletableFuture.runAsync { handler.resumeFromPause() }
            return CompletableFuture.completedFuture(null)
        }
        return CompletableFuture.runAsync { stepToNext() }
    }

    override fun stepOut(args: StepOutArguments): CompletableFuture<Void> {
        val handler = streamingHandler
        if (handler != null) {
            handler.prepareStepOut(handler.lastPausedState?.stack?.size ?: 0)
            CompletableFuture.runAsync { handler.resumeFromPause() }
            return CompletableFuture.completedFuture(null)
        }
        return CompletableFuture.runAsync { stepToNext() }
    }

    override fun continue_(args: ContinueArguments): CompletableFuture<ContinueResponse> {
        val handler = streamingHandler
        if (handler != null) {
            handler.prepareResume()
            CompletableFuture.runAsync { handler.resumeFromPause() }
            return CompletableFuture.completedFuture(
                ContinueResponse().also { it.allThreadsContinued = true },
            )
        }
        return CompletableFuture.supplyAsync {
            var hitBreakpoint = false
            while (currentIndex + 1 < snapshots.size) {
                currentIndex++
                if (snapshots[currentIndex].startLine in breakpointLines) {
                    hitBreakpoint = true
                    break
                }
            }
            if (hitBreakpoint) {
                this.client.join().stopped(
                    StoppedEventArguments().also {
                        it.reason = "breakpoint"
                        it.threadId = 1
                        it.allThreadsStopped = true
                    },
                )
            } else {
                terminateServer()
                exitServer()
            }
            ContinueResponse().also { it.allThreadsContinued = true }
        }
    }

    protected fun terminateServer() {
        this.terminateServer(null)
    }

    protected fun terminateServer(restart: Any?) {
        if (terminatedSent.compareAndSet(false, true)) {
            val terminatedEventArguments = TerminatedEventArguments()
            terminatedEventArguments.restart = restart
            this.client.join().terminated(terminatedEventArguments)
        }
    }

    protected fun exitServer() {
        this.exitServer(0)
    }

    protected fun exitServer(exitCode: Int) {
        if (exitedSent.compareAndSet(false, true)) {
            val exitedEventArguments = ExitedEventArguments()
            exitedEventArguments.exitCode = exitCode
            this.client.join().exited(exitedEventArguments)
        }
        setState(ServerState.STOPPED)
        if (!this.exited.isDone) {
            this.exited.complete(null)
        }
    }

    fun exited(): CompletableFuture<Void> {
        return this.exited
    }

    protected fun checkState(requiredState: ServerState) {
        if (this.serverState != requiredState) {
            throw IllegalStateException(
                "Operation required state $requiredState, server actual state: ${this.serverState}",
            )
        }
    }

    protected fun setState(newState: ServerState) {
        this.serverState = newState
    }

    fun getState(): ServerState {
        return this.serverState
    }

    private fun buildCqlStackFrames(handler: StreamingBreakpointHandler): List<StackFrame> {
        val reversed = handler.lastPausedCallStack.asReversed()
        val frames = mutableListOf<StackFrame>()

        val topElm = handler.lastPausedElm
        val topEntry = reversed.firstOrNull()
        val topLibId = topEntry?.libraryId ?: handler.primaryLibraryId ?: ""
        val topBounds = parseLocatorLines(topElm?.locator)
        frames.add(
            StackFrame().also { f ->
                f.id = 0
                f.name = topEntry?.def?.name ?: extractExpressionName(topElm) ?: "(unknown)"
                f.line = topBounds.startLine + 1
                f.column = topBounds.startChar + 1
                f.endLine = topBounds.endLine + 1
                f.endColumn = topBounds.endChar + 1
                f.instructionPointerReference = topElm?.localId
                f.source = resolveSource(topLibId)
            },
        )

        for (i in 1 until reversed.size) {
            val entry = reversed[i]
            val callSiteElm = reversed[i - 1].callSite ?: entry.def
            val bounds = parseLocatorLines(callSiteElm.locator)
            frames.add(
                StackFrame().also { f ->
                    f.id = i
                    f.name = entry.def.name ?: "(unknown)"
                    f.line = bounds.startLine + 1
                    f.column = bounds.startChar + 1
                    f.endLine = bounds.endLine + 1
                    f.endColumn = bounds.endChar + 1
                    f.source = resolveSource(entry.libraryId)
                },
            )
        }
        return frames
    }

    private fun resolveSource(libraryId: String): Source =
        breakpointManager.resolveSource(libraryId, streamingLaunchUri, streamingHandler, librarySourceMap, sourceReferenceRegistry)

    private fun resolveFrameLibraryId(): String =
        breakpointManager.resolveFrameLibraryId(streamingHandler)

    override fun source(args: SourceArguments): CompletableFuture<SourceResponse> {
        val ref =
            args.sourceReference ?: return CompletableFuture.completedFuture(
                SourceResponse().also { it.content = "" },
            )
        val identifier = sourceReferenceRegistry[ref]
        val content =
            identifier?.let { id ->
                try {
                    val uris =
                        contentService.locate(
                            URI.create(streamingLaunchUri ?: return@let null),
                            id,
                        )
                    val uri = uris.firstOrNull() ?: return@let null
                    contentService.read(uri)?.use { stream -> stream.bufferedReader().readText() }
                } catch (_: Exception) {
                    null
                }
            } ?: ""
        return CompletableFuture.completedFuture(SourceResponse().also { it.content = content })
    }

    @JsonRequest("setStepGranularity")
    fun setStepGranularity(args: Map<String, Any>): CompletableFuture<Void> {
        val handler = streamingHandler ?: return CompletableFuture.completedFuture(null)
        val granularity = args["granularity"] as? String
        val g =
            if (granularity?.equals("ast", ignoreCase = true) == true) {
                StreamingBreakpointHandler.StepGranularity.AST
            } else {
                StreamingBreakpointHandler.StepGranularity.CQL
            }
        handler.stepGranularity = g
        if (handler.lastPausedElm != null) {
            client.join().stopped(
                StoppedEventArguments().also {
                    it.reason = "step"
                    it.threadId = 1
                    it.allThreadsStopped = true
                },
            )
        }
        return CompletableFuture.completedFuture(null)
    }

    @JsonRequest("getAst")
    fun getAst(args: Map<String, Any>): CompletableFuture<Map<String, Any?>> {
        val uriStr = args["uri"] as? String ?: return CompletableFuture.completedFuture(mapOf("ast" to null))
        val targetUri =
            try {
                URI.create(uriStr)
            } catch (_: Exception) {
                return CompletableFuture.completedFuture(mapOf("ast" to null))
            }

        val libId = librarySourceMap.entries.firstOrNull { (_, u) -> u == targetUri }?.key

        val library =
            if (libId != null) {
                launchCompiler?.libraryManager?.compiledLibraries?.entries
                    ?.firstOrNull { (vid, _) -> vid.id == libId }
                    ?.value?.library
            } else if (streamingLaunchUri != null && targetUri.toString() == streamingLaunchUri) {
                launchCompiler?.compiledLibrary?.library
            } else {
                null
            }

        val astStr = library?.let { ElmAstLibraryWriter().writeAsString(it) }
        return CompletableFuture.completedFuture(mapOf("ast" to astStr))
    }

    // ---- Type resolution helpers ----

    /**
     * Builds a map of variable/alias name to fully qualified type name from the ELM library.
     * Covers expression definitions and aliased query sources in the primary library.
     * TODO: resolve types from included libraries.
     */
    private fun buildVariableTypeMap(compiler: CqlCompiler?): Map<String, String> =
        varResolver.buildVariableTypeMap(compiler)

    private fun collectAliasTypes(
        elm: Element?,
        map: MutableMap<String, String>,
    ) = varResolver.collectAliasTypes(elm, map)

    private fun unwrapListType(typeName: String): String =
        varResolver.unwrapListType(typeName)

    private fun profileChildrenOf(
        typeName: String,
        elementDef: BaseRuntimeElementDefinition<*>,
    ): List<BaseRuntimeChildDefinition> = varResolver.profileChildrenOf(typeName, elementDef, launchCompiler)
}
