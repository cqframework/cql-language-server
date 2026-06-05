package org.opencds.cqf.cql.debug

import ca.uhn.fhir.context.BaseRuntimeChildDefinition
import ca.uhn.fhir.context.BaseRuntimeElementDefinition
import ca.uhn.fhir.context.FhirContext
import com.google.gson.Gson
import org.cqframework.cql.cql2elm.CqlCompiler
import org.cqframework.cql.cql2elm.tracking.Trackable.resultType
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
import org.hl7.cql.model.ClassType
import org.hl7.elm.r1.AggregateExpression
import org.hl7.elm.r1.AliasedQuerySource
import org.hl7.elm.r1.BinaryExpression
import org.hl7.elm.r1.Case
import org.hl7.elm.r1.Combine
import org.hl7.elm.r1.Element
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.First
import org.hl7.elm.r1.FunctionDef
import org.hl7.elm.r1.FunctionRef
import org.hl7.elm.r1.If
import org.hl7.elm.r1.Interval
import org.hl7.elm.r1.IntervalTypeSpecifier
import org.hl7.elm.r1.Last
import org.hl7.elm.r1.LetClause
import org.hl7.elm.r1.ListTypeSpecifier
import org.hl7.elm.r1.Literal
import org.hl7.elm.r1.NamedTypeSpecifier
import org.hl7.elm.r1.NaryExpression
import org.hl7.elm.r1.ParameterDef
import org.hl7.elm.r1.Property
import org.hl7.elm.r1.Query
import org.hl7.elm.r1.Repeat
import org.hl7.elm.r1.Slice
import org.hl7.elm.r1.Sort
import org.hl7.elm.r1.TernaryExpression
import org.hl7.elm.r1.UnaryExpression
import org.hl7.fhir.instance.model.api.IBase
import org.hl7.fhir.instance.model.api.IPrimitiveType
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
import org.opencds.cqf.cql.ls.server.visitor.CqlStepPositionCollector
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

open class CqlDebugServer(
    private val compilationManager: CqlCompilationManager,
    private val contentService: ContentService,
    private val igContextManager: IgContextManager,
    private val libraryResolutionManager: LibraryResolutionManager,
) : IDebugProtocolServer, IDebugProtocolClientAware {
    companion object {
        private val log = LoggerFactory.getLogger(CqlDebugServer::class.java)
    }

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

    private val fhirContext: FhirContext by lazy { FhirContext.forR4() }

    private val varRefs = mutableMapOf<Int, Any>()
    private val varRefTypes = mutableMapOf<Int, String>()
    private var nextVarRef = 1000

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
        breakpointLines.clear()
        args.breakpoints?.forEach { breakpointLines.add(it.line - 1) }
        streamingHandler?.setBreakpoints(args.breakpoints?.map { it.line }?.toSet() ?: emptySet())
        val bps =
            args.breakpoints?.map { bp ->
                Breakpoint().also {
                    it.isVerified = true
                    it.line = bp.line
                }
            }?.toTypedArray() ?: emptyArray()
        return CompletableFuture.completedFuture(SetBreakpointsResponse().also { it.breakpoints = bps })
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
            val frame =
                when (handler.stepGranularity) {
                    StreamingBreakpointHandler.StepGranularity.CQL -> buildCqlStackFrame(handler)
                    StreamingBreakpointHandler.StepGranularity.AST -> buildAstStackFrame(handler)
                }
            return CompletableFuture.completedFuture(
                StackTraceResponse().also {
                    it.stackFrames = arrayOf(frame)
                    it.totalFrames = 1
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
                    varRefs.clear()
                    varRefTypes.clear()
                    nextVarRef = 1000
                    for (sv in registry.getStackVariables().sortedBy { it.name }) {
                        vars.add(
                            Variable().also {
                                it.name = sv.name
                                it.value = formatVariableValue(sv.value, gson)
                                it.type = variableTypeMap[sv.name]
                                it.variablesReference = registerIfExpandable(sv.value, variableTypeMap[sv.name])
                            },
                        )
                    }
                    for (cr in registry.getContextResources().sortedBy { it.name }) {
                        vars.add(
                            Variable().also {
                                it.name = cr.name
                                it.value = formatVariableValue(cr.value, gson)
                                it.type = variableTypeMap[cr.name]
                                it.variablesReference = registerIfExpandable(cr.value, variableTypeMap[cr.name])
                            },
                        )
                    }
                }
                if (args.variablesReference == 3) {
                    for (d in registry.getDefines().sortedBy { it.name }) {
                        vars.add(
                            Variable().also {
                                it.name = d.name
                                it.value = formatVariableValue(d.value, gson)
                                it.type = variableTypeMap[d.name]
                                it.variablesReference = registerIfExpandable(d.value, variableTypeMap[d.name])
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
    ): ParameterMetadata? {
        return parameterMetadata[libraryName]?.find { it.name == paramName }
    }

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
    ): EvaluateResponse {
        val candidates =
            if (frameId != null && frameId in snapshots.indices) {
                snapshots.subList(0, frameId + 1)
            } else {
                snapshots.take(currentIndex + 1)
            }
        return candidates.lastOrNull { nameMatches(it.name, expression) }
            ?.let { snap ->
                EvaluateResponse().also {
                    it.result = snap.value
                    it.variablesReference = 0
                }
            }
            ?: notAvailable()
    }

    private fun handleHoverEvaluate(
        expression: String,
        frameId: Int?,
    ): EvaluateResponse {
        // 1. Name-based define match
        val candidates =
            if (frameId != null && frameId in snapshots.indices) {
                snapshots.subList(0, frameId + 1)
            } else {
                snapshots
            }
        val defineSnapshot =
            candidates.lastOrNull { snap ->
                nameMatches(snap.name, expression)
            }
        if (defineSnapshot != null) {
            return EvaluateResponse().also {
                it.result = defineSnapshot.value
                it.variablesReference = 0
            }
        }

        // 2. Position-based sub-expression match
        if (expression.startsWith("@") && frameId != null && frameId in snapshots.indices) {
            val pos = parseHoverPosition(expression) ?: return notAvailable()
            val currentDefine = snapshots[frameId].name

            val match =
                subExpressionSnapshots
                    .filter { snap ->
                        snap.parentDefine == currentDefine && snap.contains(pos.first, pos.second)
                    }
                    .minByOrNull {
                        (it.endLine - it.startLine) * 10_000 + (it.endChar - it.startChar)
                    }

            if (match != null) {
                return EvaluateResponse().also {
                    it.result = match.value
                    it.variablesReference = 0
                }
            }
        }

        return notAvailable()
    }

    private fun nameMatches(
        snapshotName: String,
        expression: String,
    ): Boolean {
        if (snapshotName == expression) return true
        val stripped = expression.trim('"')
        if (snapshotName == stripped) return true
        val words = snapshotName.split(" ").toSet()
        if (expression in words) return true
        if (stripped in words) return true
        return false
    }

    private fun parseHoverPosition(expression: String): Pair<Int, Int>? {
        val raw = expression.removePrefix("@")
        val parts = raw.split(":").mapNotNull { it.toIntOrNull() }
        return if (parts.size == 2) parts[0] to parts[1] else null
    }

    private fun extractExpressionName(elm: Element?): String? {
        if (elm is ExpressionDef) return elm.name
        return elm?.javaClass?.simpleName
    }

    /**
     * Parses a TrackBack locator string (1-indexed, e.g. "10:12-10:24") into
     * (startLine, startChar, endLine, endChar) in 0-indexed DAP coordinates.
     * Returns all zeros when the locator is null or unparseable.
     */
    private data class LocatorBounds(val startLine: Int, val startChar: Int, val endLine: Int, val endChar: Int)

    private fun parseLocatorLines(locator: String?): LocatorBounds {
        if (locator == null) return LocatorBounds(0, 0, 0, 0)
        val parts = locator.split("-").takeIf { it.size == 2 } ?: return LocatorBounds(0, 0, 0, 0)
        val (sl, sc) = parts[0].split(":").takeIf { it.size == 2 }?.map { it.toIntOrNull() } ?: return LocatorBounds(0, 0, 0, 0)
        val (el, ec) = parts[1].split(":").takeIf { it.size == 2 }?.map { it.toIntOrNull() } ?: return LocatorBounds(0, 0, 0, 0)
        if (sl == null || sc == null || el == null || ec == null) return LocatorBounds(0, 0, 0, 0)
        return LocatorBounds(sl - 1, sc - 1, el - 1, ec)
    }

    private fun formatVariableValue(
        value: Any?,
        gson: Gson,
    ): String {
        val unwrapped = value
        if (unwrapped !== value) return formatVariableValue(unwrapped, gson)
        return when (value) {
            null -> "null"
            is String -> "\"$value\""
            is Boolean, is Number -> value.toString()
            is IBase ->
                try {
                    fhirContext.newJsonParser().encodeToString(value)
                } catch (_: Exception) {
                    value.toString()
                }
            else ->
                try {
                    gson.toJson(value)
                } catch (_: Exception) {
                    value.toString()
                }
        }
    }

    private fun formatPropertyValue(
        value: Any?,
        gson: Gson,
    ): String {
        if (value is org.hl7.fhir.r4.model.Period) {
            return formatPeriodAsInterval(value)
        }
        return formatVariableValue(value, gson)
    }

    private fun isExpandable(value: Any?): Boolean {
        if (value == null) return false
        if (value is IPrimitiveType<*>) return false
        if (value is IBase) return true
        if (value is List<*> && value.isNotEmpty()) return true
        return false
    }

    private fun registerIfExpandable(
        value: Any?,
        typeName: String? = null,
    ): Int {
        if (!isExpandable(value)) return 0
        val ref = nextVarRef++
        varRefs[ref] = value!!
        if (typeName != null) varRefTypes[ref] = typeName
        return ref
    }

    private fun childrenOf(
        value: Any,
        typeName: String? = null,
    ): List<Variable> {
        val gson = Gson()
        return when (value) {
            is IBase -> {
                if (value is IPrimitiveType<*>) {
                    return emptyList()
                }
                val elementDef = fhirContext.getElementDefinition(value.javaClass) as? BaseRuntimeElementDefinition<*>
                if (elementDef != null) {
                    val children: List<BaseRuntimeChildDefinition> =
                        if (typeName != null) {
                            profileChildrenOf(typeName, elementDef)
                        } else {
                            elementDef.children ?: emptyList()
                        }
                    children.flatMap { child ->
                        val accessor = child.getAccessor()
                        val childValues: List<IBase> =
                            try {
                                @Suppress("UNCHECKED_CAST")
                                accessor.getValues(value) as? List<IBase> ?: emptyList()
                            } catch (_: Exception) {
                                emptyList()
                            }
                        val childName = child.elementName
                        if (childValues.size == 1) {
                            listOf(
                                Variable().also {
                                    it.name = childName
                                    it.value = formatVariableValue(childValues[0], gson)
                                    it.variablesReference = registerIfExpandable(childValues[0])
                                },
                            )
                        } else {
                            childValues.mapIndexed { index, childValue ->
                                Variable().also {
                                    it.name = "$childName[$index]"
                                    it.value = formatVariableValue(childValue, gson)
                                    it.variablesReference = registerIfExpandable(childValue)
                                }
                            }
                        }
                    }
                } else {
                    emptyList()
                }
            }
            is List<*> -> {
                value.mapIndexed { index, item ->
                    Variable().also {
                        it.name = "[$index]"
                        it.value = formatVariableValue(item, gson)
                        it.variablesReference = registerIfExpandable(item)
                    }
                }
            }
            else -> emptyList()
        }
    }

    /**
     * Searches the expanded variable tree (varRefs) for a child variable whose display
     * name matches [name]. This handles copy-value for expanded FHIR resource children
     * (e.g., "name[0]", "given[0]") that are not top-level stack/context/define values.
     */
    private fun findInVarRefs(name: String): EvaluateResponse? {
        for ((ref, value) in varRefs) {
            val result = findInVarRefsChildren(name, value, varRefTypes[ref])
            if (result != null) return result
        }
        return null
    }

    private fun findInVarRefsChildren(
        name: String,
        value: Any,
        typeName: String?,
    ): EvaluateResponse? {
        val children = childrenOf(value, typeName)
        for (child in children) {
            if (child.name == name) {
                return EvaluateResponse().also {
                    it.result = child.value
                    it.variablesReference = child.variablesReference
                }
            }
            if (child.variablesReference > 0) {
                val childValue = varRefs[child.variablesReference] ?: continue
                val childType = varRefTypes[child.variablesReference]
                val result = findInVarRefsChildren(name, childValue, childType)
                if (result != null) return result
            }
        }
        return null
    }

    private fun resolvePropertyValue(
        property: Property,
        state: org.opencds.cqf.cql.engine.execution.State,
        gson: Gson,
    ): PropertyResult? {
        val sourceRef = property.source as? org.hl7.elm.r1.ExpressionRef ?: return null
        val sourceName = sourceRef.name ?: return null
        val sourceLibrary = sourceRef.libraryName
        val propertyName = property.path ?: return null

        val sourceValue =
            streamingHandler?.runtimeRegistry?.find(sourceName, sourceLibrary)?.value
                ?: state.contextValues[sourceName]
                ?: state.stack.flatMap { frame -> frame.variables }.find { v -> v.name == sourceName }?.value
                ?: return null

        return when (sourceValue) {
            is List<*> -> {
                val pairs =
                    sourceValue.mapNotNull { item ->
                        if (item is IBase) {
                            val id = getResourceId(item)
                            val pv = extractPropertyValue(item, propertyName)
                            if (pv != null) id to pv else null
                        } else {
                            null
                        }
                    }
                if (pairs.isEmpty()) {
                    null
                } else {
                    val display =
                        pairs.joinToString(", ") { (id, pv) ->
                            "$id: ${formatPropertyValue(pv, gson)}"
                        }
                    PropertyResult("[$display]", sourceValue as List<*>)
                }
            }
            is IBase -> {
                val pv = extractPropertyValue(sourceValue, propertyName) ?: return null
                PropertyResult(formatPropertyValue(pv, gson), pv)
            }
            else -> null
        }
    }

    private fun resolveFromCursorCategory(
        category: CursorCategory,
        state: State,
        handler: StreamingBreakpointHandler,
        gson: Gson,
    ): EvaluateResponse? {
        return when (category) {
            is CursorCategory.AliasReference -> {
                val rv = handler.runtimeRegistry.find(category.name)
                if (rv != null) {
                    EvaluateResponse().also {
                        it.result = formatVariableValue(rv.value, gson)
                        it.variablesReference = registerIfExpandable(rv.value)
                    }
                } else {
                    null
                }
            }
            is CursorCategory.OperandRef -> {
                val rv = handler.runtimeRegistry.find(category.name)
                if (rv != null) {
                    EvaluateResponse().also {
                        it.result = formatVariableValue(rv.value, gson)
                        it.variablesReference = registerIfExpandable(rv.value)
                    }
                } else {
                    null
                }
            }
            is CursorCategory.ExpressionRef -> {
                val rv =
                    if (category.libraryName != null) {
                        handler.runtimeRegistry.find(category.name, category.libraryName)
                    } else {
                        handler.runtimeRegistry.find(category.name)
                    }
                if (rv != null) {
                    EvaluateResponse().also {
                        it.result = formatVariableValue(rv.value, gson)
                        it.variablesReference = registerIfExpandable(rv.value)
                    }
                } else {
                    null
                }
            }
            is CursorCategory.ParameterRef -> {
                val rv = handler.runtimeRegistry.find(category.name)
                if (rv != null) {
                    EvaluateResponse().also {
                        it.result = formatVariableValue(rv.value, gson)
                        it.variablesReference = registerIfExpandable(rv.value)
                    }
                } else {
                    null
                }
            }
            is CursorCategory.PropertyName -> {
                if (category.aliasName != null) {
                    val result = resolvePropertyFromAlias(category.aliasName, category.name, state, handler, gson)
                    if (result != null) {
                        EvaluateResponse().also {
                            it.result = result.displayString
                            it.variablesReference = registerIfExpandable(result.expandableValue)
                        }
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private data class PropertyResult(val displayString: String, val expandableValue: Any?)

    private fun resolvePropertyFromAlias(
        aliasName: String,
        propertyName: String,
        state: State,
        handler: StreamingBreakpointHandler,
        gson: Gson,
    ): PropertyResult? {
        val sourceValue =
            handler.runtimeRegistry.find(aliasName)?.value
                ?: return null

        return when (sourceValue) {
            is List<*> -> {
                val pairs =
                    sourceValue.mapNotNull { item ->
                        if (item is IBase) {
                            val id = getResourceId(item)
                            val pv = extractPropertyValue(item, propertyName)
                            if (pv != null) id to pv else null
                        } else {
                            null
                        }
                    }
                if (pairs.isEmpty()) {
                    null
                } else {
                    val display =
                        pairs.joinToString(", ") { (id, pv) ->
                            "$id: ${formatPropertyValue(pv, gson)}"
                        }
                    PropertyResult("[$display]", sourceValue as List<*>)
                }
            }
            is IBase -> {
                val pv = extractPropertyValue(sourceValue, propertyName) ?: return null
                PropertyResult(formatPropertyValue(pv, gson), pv)
            }
            else -> null
        }
    }

    private fun extractPropertyValue(
        resource: IBase,
        propertyName: String,
    ): Any? {
        return try {
            val getter = resource.javaClass.getMethod("get${propertyName.replaceFirstChar { it.uppercase() }}")
            getter.invoke(resource)
        } catch (_: Exception) {
            null
        }
    }

    private fun getResourceId(resource: IBase): String {
        return try {
            // Try FHIR R4 way first
            val idMethod = resource.javaClass.getMethod("getIdElement")
            val idElement = idMethod.invoke(resource)
            val idPartMethod = idElement.javaClass.getMethod("getIdPart")
            idPartMethod.invoke(idElement) as? String ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun getFhirContextForVersion(version: String?): FhirContext {
        return when (version?.uppercase()) {
            "DSTU3", "STU3" -> FhirContext.forDstu3()
            "R5" -> FhirContext.forR5()
            else -> fhirContext
        }
    }

    private fun buildTestCaseVariables(): List<Variable> {
        val testCaseList = mutableListOf<Variable>()
        val testCaseUri = launchArgs?.testCaseUri
        if (!testCaseUri.isNullOrEmpty()) {
            try {
                // testCaseUri is a well-formed file: URI from VS Code launch args
                val testCasePath = Paths.get(URI.create(testCaseUri))
                if (Files.exists(testCasePath) && Files.isDirectory(testCasePath)) {
                    val context = getFhirContextForVersion(launchArgs?.fhirVersion)
                    val gson = Gson()
                    Files.newDirectoryStream(testCasePath) { path ->
                        val name = path.fileName.toString().lowercase()
                        name.endsWith(".json") || name.endsWith(".xml")
                    }.use { stream ->
                        for (file in stream) {
                            try {
                                val content = Files.readString(file)
                                val fileName = file.fileName.toString().lowercase()
                                val parser = if (fileName.endsWith(".json")) context.newJsonParser() else context.newXmlParser()
                                val resource = parser.parseResource(content)
                                val resourceType = resource.fhirType()
                                val idPart = resource.idElement?.idPart
                                val varName =
                                    if (!idPart.isNullOrEmpty()) {
                                        "$resourceType/$idPart"
                                    } else {
                                        file.fileName.toString().removeSuffix(".json").removeSuffix(".xml")
                                    }
                                testCaseList.add(
                                    Variable().also {
                                        it.name = varName
                                        it.value = formatVariableValue(resource, gson)
                                        it.type = resourceType
                                        it.variablesReference = registerIfExpandable(resource)
                                    },
                                )
                            } catch (_: Exception) {
                                // Ignore malformed files
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore path resolution errors
            }
        }
        testCaseList.sortBy { it.name }
        return testCaseList
    }

    private fun formatPeriodAsInterval(period: org.hl7.fhir.r4.model.Period): String {
        val gson = Gson()
        val start = period.start?.let { formatVariableValue(it, gson) } ?: "null"
        val end = period.end?.let { formatVariableValue(it, gson) } ?: "null"
        return "[$start, $end)"
    }

    private fun notAvailable(): EvaluateResponse =
        EvaluateResponse().also {
            it.result = "not available"
            it.variablesReference = 0
        }

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
            return CompletableFuture.runAsync {
                handler.stepOver(handler.lastPausedState?.stack?.size ?: 0)
            }
        }
        return CompletableFuture.runAsync { stepToNext() }
    }

    override fun stepIn(args: StepInArguments): CompletableFuture<Void> {
        val handler = streamingHandler
        if (handler != null) {
            return CompletableFuture.runAsync { handler.stepIn() }
        }
        return CompletableFuture.runAsync { stepToNext() }
    }

    override fun stepOut(args: StepOutArguments): CompletableFuture<Void> {
        val handler = streamingHandler
        if (handler != null) {
            return CompletableFuture.runAsync {
                handler.stepOut(handler.lastPausedState?.stack?.size ?: 0)
            }
        }
        return CompletableFuture.runAsync { stepToNext() }
    }

    override fun continue_(args: ContinueArguments): CompletableFuture<ContinueResponse> {
        val handler = streamingHandler
        if (handler != null) {
            return CompletableFuture.supplyAsync {
                handler.resume()
                ContinueResponse().also { it.allThreadsContinued = true }
            }
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

    private fun buildCqlStackFrame(handler: StreamingBreakpointHandler): StackFrame {
        val elm = handler.lastPausedElm
        val locator = elm?.locator
        val bounds = parseLocatorLines(locator)
        val name = extractExpressionName(elm)
        val sourceUri = streamingLaunchUri?.let { Paths.get(URI.create(it)).toString() }
        return StackFrame().also { f ->
            f.id = 0
            f.name = name ?: "(unknown)"
            f.line = bounds.startLine + 1
            f.column = bounds.startChar + 1
            f.endLine = bounds.endLine + 1
            f.endColumn = bounds.endChar + 1
            f.source = sourceUri?.let { Source().also { s -> s.path = it } }
        }
    }

    private fun buildAstStackFrame(handler: StreamingBreakpointHandler): StackFrame {
        val elm = handler.lastPausedElm
        val locator = elm?.locator
        val bounds = parseLocatorLines(locator)
        val sourceUri = streamingLaunchUri?.let { Paths.get(URI.create(it)).toString() }
        return StackFrame().also { f ->
            f.id = 0
            f.name = elm?.javaClass?.simpleName ?: "(unknown)"
            f.line = bounds.startLine + 1
            f.column = bounds.startChar + 1
            f.endLine = bounds.endLine + 1
            f.endColumn = bounds.endChar + 1
            f.instructionPointerReference = elm?.localId
            f.source = sourceUri?.let { Source().also { s -> s.path = it } }
        }
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

    // ---- Type resolution helpers ----

    /**
     * Builds a map of variable/alias name to fully qualified type name from the ELM library.
     * Covers expression definitions and aliased query sources in the primary library.
     * TODO: resolve types from included libraries.
     */
    private fun buildVariableTypeMap(compiler: CqlCompiler?): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val library = compiler?.library ?: return map
        val defs = library.statements?.def ?: return map
        for (def in defs) {
            if (def.name != null && def.resultType != null) {
                map[def.name!!] = def.resultType.toString()
            }
            collectAliasTypes(def.expression, map)
        }
        return map
    }

    private fun collectAliasTypes(
        elm: Element?,
        map: MutableMap<String, String>,
    ) {
        if (elm == null) return
        when (elm) {
            is AliasedQuerySource -> {
                if (elm.alias != null && elm.resultType != null) {
                    map[elm.alias!!] = elm.resultType.toString()
                }
                collectAliasTypes(elm.expression, map)
            }
            is LetClause -> {
                if (elm.identifier != null && elm.resultType != null) {
                    map[elm.identifier!!] = elm.resultType.toString()
                }
            }
            is Query -> {
                elm.source.forEach { collectAliasTypes(it, map) }
                elm.relationship.forEach { collectAliasTypes(it, map) }
                elm.let?.forEach { collectAliasTypes(it, map) }
            }
            is UnaryExpression -> collectAliasTypes(elm.operand, map)
            is BinaryExpression -> elm.operand.forEach { collectAliasTypes(it, map) }
            is TernaryExpression -> elm.operand.forEach { collectAliasTypes(it, map) }
            is NaryExpression -> elm.operand.forEach { collectAliasTypes(it, map) }
            is AggregateExpression -> collectAliasTypes(elm.source, map)
            is Last -> collectAliasTypes(elm.source, map)
            is First -> collectAliasTypes(elm.source, map)
            is If -> {
                collectAliasTypes(elm.then, map)
                collectAliasTypes(elm.`else`, map)
                collectAliasTypes(elm.condition, map)
            }
            is FunctionRef -> elm.operand.forEach { collectAliasTypes(it, map) }
            is Sort -> collectAliasTypes(elm.source, map)
            is Slice -> collectAliasTypes(elm.source, map)
            is Case -> {
                collectAliasTypes(elm.comparand, map)
                elm.caseItem?.forEach { item ->
                    collectAliasTypes(item.then, map)
                    collectAliasTypes(item.`when`, map)
                }
                collectAliasTypes(elm.`else`, map)
            }
            is Repeat -> {
                collectAliasTypes(elm.source, map)
                collectAliasTypes(elm.element, map)
            }
            is Property -> collectAliasTypes(elm.source, map)
            is Combine -> collectAliasTypes(elm.source, map)
        }
    }

    /**
     * Strips a "list<...>" wrapper from a type name for profile resolution.
     * "list<QICore.ObservationCancelled>" → "QICore.ObservationCancelled"
     * "QICore.ObservationCancelled" → "QICore.ObservationCancelled"
     */
    private fun unwrapListType(typeName: String): String {
        val trimmed = typeName.trim()
        return if (trimmed.startsWith("list<", ignoreCase = true) && trimmed.endsWith(">")) {
            trimmed.removePrefix("list<").removeSuffix(">").trim()
        } else {
            trimmed
        }
    }

    /**
     * Resolves a profile's ClassType from the cached compiler and returns the
     * HAPI child definitions in profile-specified order. Falls back to HAPI's
     * own child list when the type is not a known profile.
     */
    private fun profileChildrenOf(
        typeName: String,
        elementDef: BaseRuntimeElementDefinition<*>,
    ): List<BaseRuntimeChildDefinition> {
        val compiler = launchCompiler ?: return elementDef.children ?: emptyList()
        val modelManager = compiler.libraryManager?.modelManager ?: return elementDef.children ?: emptyList()
        val profileName = unwrapListType(typeName)
        val model =
            modelManager.globalCache.values.firstOrNull { it.resolveTypeName(profileName) != null }
                ?: return elementDef.children ?: emptyList()
        val classType =
            model.resolveTypeName(profileName) as? ClassType
                ?: return elementDef.children ?: emptyList()
        val allChildren = elementDef.children ?: emptyList()
        return classType.sortedElements.mapNotNull { element ->
            allChildren.firstOrNull { it.elementName == element.name }
        }
    }
}
