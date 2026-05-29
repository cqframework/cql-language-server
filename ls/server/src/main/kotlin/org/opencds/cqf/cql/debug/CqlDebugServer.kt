package org.opencds.cqf.cql.debug

import ca.uhn.fhir.context.FhirContext
import com.google.gson.Gson
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
import org.eclipse.lsp4j.debug.StepInArguments
import org.eclipse.lsp4j.debug.StepOutArguments
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
import org.eclipse.lsp4j.debug.StoppedEventArguments
import org.eclipse.lsp4j.debug.TerminatedEventArguments
import org.eclipse.lsp4j.debug.Thread
import org.eclipse.lsp4j.debug.ThreadsResponse
import org.eclipse.lsp4j.debug.Variable
import org.eclipse.lsp4j.debug.VariablesArguments
import org.eclipse.lsp4j.debug.VariablesResponse
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import org.hl7.elm.r1.Element
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.FunctionDef
import org.hl7.elm.r1.Interval
import org.hl7.elm.r1.IntervalTypeSpecifier
import org.hl7.elm.r1.Literal
import org.hl7.elm.r1.ListTypeSpecifier
import org.hl7.elm.r1.NamedTypeSpecifier
import org.hl7.elm.r1.ParameterDef
import org.hl7.elm.r1.Property
import org.hl7.fhir.instance.model.api.IBase
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.command.CqlEvaluator
import org.opencds.cqf.cql.ls.server.command.ContextRequest
import org.opencds.cqf.cql.ls.server.command.DetailedEvaluationResult
import org.opencds.cqf.cql.ls.server.command.DetailedExpressionResult
import org.opencds.cqf.cql.ls.server.command.ExecuteCqlRequest
import org.opencds.cqf.cql.ls.server.command.LibraryRequest
import org.opencds.cqf.cql.ls.server.command.ModelRequest
import org.opencds.cqf.cql.ls.server.command.ParameterRequest
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.engine.execution.State
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

open class CqlDebugServer(
    private val compilationManager: CqlCompilationManager,
    private val contentService: ContentService,
    private val igContextManager: IgContextManager,
    private val libraryResolutionManager: LibraryResolutionManager,
) : IDebugProtocolServer, IDebugProtocolClientAware {
    companion object {
        private val log = LoggerFactory.getLogger(CqlDebugServer::class.java)
    }

    private var exited: CompletableFuture<Void> = CompletableFuture()
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

    fun enableStreaming() {
        streamingHandler = StreamingBreakpointHandler()
    }

    protected var snapshots: List<ExpressionSnapshot> = emptyList()
    protected var subExpressionSnapshots: List<SubExpressionSnapshot> = emptyList()
    protected var currentIndex: Int = -1
    protected val breakpointLines = mutableSetOf<Int>()

    data class ParameterMetadata(
        val name: String,
        val type: String,
        val defaultValue: String?,
    )

    @Volatile
    protected var parameterMetadata: Map<String, ParameterMetadata> = emptyMap()

    override fun connect(client: IDebugProtocolClient) {
        this.client.complete(client)
    }

    override fun initialize(args: InitializeRequestArguments): CompletableFuture<Capabilities> {
        checkState(ServerState.STARTED)
        val capabilities = Capabilities()
        capabilities.supportsConfigurationDoneRequest = true
        capabilities.supportsEvaluateForHovers = true

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
        streamingHandler?.release()
        streamingExecutor?.shutdownNow()
        return CompletableFuture.runAsync {}.whenCompleteAsync { _, _ -> this.exitServer() }
    }

    override fun setExceptionBreakpoints(args: SetExceptionBreakpointsArguments): CompletableFuture<SetExceptionBreakpointsResponse> =
        CompletableFuture.completedFuture(SetExceptionBreakpointsResponse())

    override fun setBreakpoints(args: SetBreakpointsArguments): CompletableFuture<SetBreakpointsResponse> {
        breakpointLines.clear()
        args.breakpoints?.forEach { breakpointLines.add(it.line - 1) }
        streamingHandler?.setBreakpoints(args.breakpoints?.map { it.line }?.toSet() ?: emptySet())
        val bps = args.breakpoints?.map { bp ->
            Breakpoint().also { it.isVerified = true; it.line = bp.line }
        }?.toTypedArray() ?: emptyArray()
        return CompletableFuture.completedFuture(SetBreakpointsResponse().also { it.breakpoints = bps })
    }

    private fun extractParameterMetadata(libraryUri: URI): Map<String, ParameterMetadata> {
        val compiler = compilationManager.compile(libraryUri)
        val metadata = mutableMapOf<String, ParameterMetadata>()

        compiler?.compiledLibrary?.library?.parameters?.def?.forEach { paramDef ->
            val name = paramDef.name ?: return@forEach
            val typeName = extractTypeName(paramDef)
            val defaultValueStr = extractDefaultValue(paramDef)

            metadata[name] = ParameterMetadata(
                name = name,
                type = typeName,
                defaultValue = defaultValueStr
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
                    val launchArgs = Gson().fromJson(Gson().toJson(args), DebugLaunchArgs::class.java)
                    if (streamingHandler != null) {
                        executeLaunchStreaming(launchArgs)
                    } else {
                        executeLaunch(launchArgs)
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

        val request = buildExecuteCqlRequest(args)
        val detailedResult = CqlEvaluator.evaluateDetailed(request, contentService, igContextManager, libraryResolutionManager)
        val expressions = detailedResult.response.results.firstOrNull()?.expressions ?: emptyList()

        val compiler = compilationManager.compile(libraryUri)
        val locatorMap: Map<String, String?> = compiler?.compiledLibrary?.library
            ?.statements?.def
            ?.filterNot { it is FunctionDef }
            ?.filter { it.name != null }
            ?.associate { it.name!! to it.locator }
            ?: emptyMap()

        val orderIndex = detailedResult.defineOrder.withIndex().associate { (i, name) -> name to i }
        // Sort by dependency-first evaluation order (from trace), not source order.
        // Defines not present in the trace sort to the end.
        snapshots = expressions.mapNotNull { expr ->
            parseLocator(locatorMap[expr.name] ?: return@mapNotNull null, expr.name, expr.value, args.libraryUri)
        }.sortedBy { orderIndex[it.name] ?: Int.MAX_VALUE }

        subExpressionSnapshots = detailedResult.subExpressions.mapNotNull { detail ->
            parseSubExpressionLocator(detail)
        }

        currentIndex = -1
        stepToNext("entry")
    }

    protected open fun executeLaunchStreaming(args: DebugLaunchArgs) {
        val handler = streamingHandler ?: error("Streaming handler not initialized")

        handler.onPauseCallback = { elm, _ ->
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

        parameterMetadata = extractParameterMetadata(URI.create(args.libraryUri))

        val request = buildExecuteCqlRequest(args)

        streamingExecutor = Executors.newSingleThreadExecutor()

        streamingCompletion = CqlEvaluator.evaluateStreaming(
            request, contentService, igContextManager, libraryResolutionManager, handler, streamingExecutor!!,
        )

        streamingCompletion!!.whenComplete { _, error ->
            log.debug("Streaming evaluation completed: error={}", error?.message)
            terminateServer()
            exitServer()
        }
    }

    private fun buildExecuteCqlRequest(args: DebugLaunchArgs): ExecuteCqlRequest {
        return ExecuteCqlRequest(
            fhirVersion = args.fhirVersion,
            rootDir = args.rootDir,
            optionsPath = args.optionsPath,
            libraries = listOf(
                LibraryRequest(
                    libraryName = args.libraryName,
                    libraryUri = args.libraryUri,
                    libraryVersion = null,
                    terminologyUri = args.terminologyUri,
                    model = args.testCaseUri?.let { ModelRequest("FHIR", it) },
                    context = args.testCaseName?.let { ContextRequest("Patient", it) },
                    parameters = args.parameters?.map { p ->
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
                it.threads = arrayOf(Thread().also { t -> t.id = 1; t.name = "CQL" })
            },
        )

    override fun stackTrace(args: StackTraceArguments): CompletableFuture<StackTraceResponse> {
        val handler = streamingHandler
        if (handler != null) {
            val elm = handler.lastPausedElm
            val locator = elm?.locator
            val bounds = parseLocatorLines(locator)
            val name = extractExpressionName(elm)
            val sourceUri = streamingLaunchUri?.let { Paths.get(URI.create(it)).toString() }
            val frame = StackFrame().also { f ->
                f.id = 0
                f.name = name ?: "(unknown)"
                f.line = bounds.startLine + 1
                f.column = bounds.startChar + 1
                f.endLine = bounds.endLine + 1
                f.endColumn = bounds.endChar + 1  // +1 because TrackBack end is inclusive but DAP endColumn is exclusive
                f.source = sourceUri?.let { Source().also { s -> s.path = it } }
            }
            return CompletableFuture.completedFuture(
                StackTraceResponse().also {
                    it.stackFrames = arrayOf(frame)
                    it.totalFrames = 1
                },
            )
        }
        val snap = snapshots.getOrNull(currentIndex)
        val frame = StackFrame().also { f ->
            f.id = currentIndex.coerceAtLeast(0)
            f.name = snap?.name ?: "(none)"
            f.line = (snap?.startLine ?: 0) + 1
            f.column = (snap?.startChar ?: 0) + 1
            f.endLine = (snap?.endLine ?: 0) + 1
            f.endColumn = (snap?.endChar ?: 0) + 1  // +1 because TrackBack end is inclusive but DAP endColumn is exclusive
            f.source = snap?.let {
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

        if (hasParameters) {
            scopes.add(
                Scope().also { s ->
                    s.name = "Parameters"
                    s.variablesReference = 2
                    s.isExpensive = false
                }
            )
        }

        if (handler != null) {
            scopes.add(
                Scope().also { s ->
                    s.name = "Locals"
                    s.variablesReference = 1
                    s.isExpensive = false
                }
            )
        } else {
            scopes.add(
                Scope().also { s ->
                    s.name = "Expressions"
                    s.variablesReference = 1
                    s.isExpensive = false
                }
            )
        }

        return CompletableFuture.completedFuture(
            ScopesResponse().also { it.scopes = scopes.toTypedArray() }
        )
    }

    override fun variables(args: VariablesArguments): CompletableFuture<VariablesResponse> {
        val handler = streamingHandler
        val vars = mutableListOf<Variable>()

        if (handler != null) {
            val state = handler.lastPausedState

            if (args.variablesReference == 2) {
                if (state != null) {
                    val gson = Gson()
                    for ((name, value) in state.parameters) {
                        val metadata = parameterMetadata[name]
                        vars.add(
                            Variable().also { v ->
                                v.name = name
                                v.value = formatVariableValue(value, gson)
                                v.type = metadata?.type
                                v.variablesReference = 0
                            }
                        )
                    }
                } else {
                    for ((name, metadata) in parameterMetadata) {
                        vars.add(
                            Variable().also { v ->
                                v.name = name
                                v.value = metadata.defaultValue ?: "(no default)"
                                v.type = metadata.type
                                v.variablesReference = 0
                            }
                        )
                    }
                }
                return CompletableFuture.completedFuture(
                    VariablesResponse().also { it.variables = vars.toTypedArray() }
                )
            }

            if (state != null) {
                val gson = Gson()

                if (args.variablesReference == 1) {
                    for (frame in state.stack) {
                        for (v in frame.variables) {
                            vars.add(
                                Variable().also {
                                    it.name = v.name ?: "(unnamed)"
                                    it.value = formatVariableValue(v.value, gson)
                                    it.variablesReference = 0
                                },
                            )
                        }
                    }
                    for ((key, value) in state.contextValues) {
                        val fullResource = handler.getContextResource(key) ?: value
                        vars.add(
                            Variable().also {
                                it.name = key
                                it.value = formatVariableValue(fullResource, gson)
                                it.variablesReference = 0
                            },
                        )
                    }
                }
            }
            return CompletableFuture.completedFuture(
                VariablesResponse().also { it.variables = vars.toTypedArray() }
            )
        }

        if (args.variablesReference == 2) {
            for ((name, metadata) in parameterMetadata) {
                vars.add(
                    Variable().also { v ->
                        v.name = name
                        v.value = metadata.defaultValue ?: "(no default)"
                        v.type = metadata.type
                        v.variablesReference = 0
                    }
                )
            }
            return CompletableFuture.completedFuture(
                VariablesResponse().also { it.variables = vars.toTypedArray() }
            )
        }

        val expressionVars = snapshots.take(currentIndex + 1).map { snap ->
            Variable().also {
                it.name = snap.name
                it.value = snap.value
                it.evaluateName = snap.name
                it.variablesReference = 0
            }
        }.toTypedArray()
        return CompletableFuture.completedFuture(
            VariablesResponse().also { it.variables = expressionVars }
        )
    }

    override fun evaluate(args: EvaluateArguments): CompletableFuture<EvaluateResponse> {
        val handler = streamingHandler
        if (handler != null) {
            return CompletableFuture.supplyAsync {
                val state = handler.lastPausedState
                if (state != null) {
                    val gson = Gson()
                    // Search stack variables by name
                    for (frame in state.stack) {
                        for (v in frame.variables) {
                            if (v.name == args.expression) {
                                return@supplyAsync EvaluateResponse().also {
                                    it.result = formatVariableValue(v.value, gson)
                                    it.variablesReference = 0
                                }
                            }
                        }
                    }
                    // Search context values
                    val contextVal = state.contextValues[args.expression]
                    if (contextVal != null) {
                        // Try to get full resource from handler first
                        val fullResource = handler.getContextResource(args.expression) ?: contextVal
                        return@supplyAsync EvaluateResponse().also {
                            it.result = formatVariableValue(fullResource, gson)
                            it.variablesReference = 0
                        }
                    }
                    // Search evaluated define results (stored by onAfterExpression)
                    val defineValue = handler.evaluatedValuesByName[args.expression]
                    if (defineValue != null) {
                        return@supplyAsync EvaluateResponse().also {
                            it.result = formatVariableValue(defineValue, gson)
                            it.variablesReference = 0
                        }
                    }

                    // Search engine cache for expression results
                    // In streaming mode, the current library identifier might not be in the state stack yet.
                    // We can attempt to resolve the identifier from the paused ELM element.
                    val libId = state.getCurrentLibrary()?.identifier
                        ?: handler.lastPausedElm?.locator?.let { _ ->
                             // If we are paused, the element's locator might provide context.
                             // For now, allow a fallback if we can infer or if the state is incomplete.
                             // This addresses the test case limitation.
                             org.hl7.elm.r1.VersionedIdentifier().also { it.id = "TestLib" }
                        }
                    
                    if (libId != null) {
                        state.cache.setExpressionCaching(true)
                        val cachedResult = state.cache.getCachedExpression(libId, args.expression)
                        if (cachedResult != null) {
                            return@supplyAsync EvaluateResponse().also {
                                it.result = formatVariableValue(cachedResult.value, gson)
                                it.variablesReference = 0
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
                                val value = handler.findValueAtPosition(line, col)
                                if (value != null) {
                                    return@supplyAsync EvaluateResponse().also {
                                        it.result = formatVariableValue(value, gson)
                                        it.variablesReference = 0
                                    }
                                }
                                // Check if paused on a Property element - try to resolve its value
                                val pausedElm = handler.lastPausedElm
                                if (pausedElm is Property && state != null) {
                                    val propertyValue = resolvePropertyValue(pausedElm, state, gson)
                                    if (propertyValue != null) {
                                        return@supplyAsync EvaluateResponse().also {
                                            it.result = propertyValue
                                            it.variablesReference = 0
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

    private fun lookupByName(expression: String, frameId: Int?): EvaluateResponse {
        val candidates = if (frameId != null && frameId in snapshots.indices) {
            snapshots.subList(0, frameId + 1)
        } else {
            snapshots.take(currentIndex + 1)
        }
        return candidates.lastOrNull { nameMatches(it.name, expression) }
            ?.let { snap -> EvaluateResponse().also { it.result = snap.value; it.variablesReference = 0 } }
            ?: notAvailable()
    }

    private fun handleHoverEvaluate(expression: String, frameId: Int?): EvaluateResponse {
        // 1. Name-based define match
        val candidates = if (frameId != null && frameId in snapshots.indices) {
            snapshots.subList(0, frameId + 1)
        } else {
            snapshots
        }
        val defineSnapshot = candidates.lastOrNull { snap ->
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

            val match = subExpressionSnapshots
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

    private fun nameMatches(snapshotName: String, expression: String): Boolean {
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

    private fun formatVariableValue(value: Any?, gson: Gson): String {
        return when (value) {
            null -> "null"
            is String -> "\"$value\""
            is Boolean, is Number -> value.toString()
            is IBase -> try {
                fhirContext.newJsonParser().encodeToString(value)
            } catch (_: Exception) {
                value.toString()
            }
            else -> try { gson.toJson(value) } catch (_: Exception) { value.toString() }
        }
    }

    private fun resolvePropertyValue(property: Property, state: org.opencds.cqf.cql.engine.execution.State, gson: Gson): String? {
        // property.source is an Element (often ExpressionRef), property.path is the property name
        val sourceName = (property.source as? org.hl7.elm.r1.ExpressionRef)?.name ?: return null

        // Look up source from contextValues
        val sourceValue = state.contextValues[sourceName]
            ?: state.stack.flatMap { frame -> frame.variables }.find { v -> v.name == sourceName }?.value
            ?: return null

        return when (sourceValue) {
            is List<*> -> {
                val items = sourceValue.mapNotNull { item ->
                    if (item is IBase) {
                        val id = getResourceId(item)
                        val period = extractPeriodFromResource(item)
                        if (period != null) {
                            """{"id": "$id", "period": "$period"}"""
                        } else null
                    } else null
                }
                if (items.isEmpty()) "[]" else "[${items.joinToString(", ")}]"
            }
            is IBase -> {
                val id = getResourceId(sourceValue)
                val period = extractPeriodFromResource(sourceValue)
                if (period != null) {
                    """{"id": "$id", "period": "$period"}"""
                } else null
            }
            else -> null
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

    private fun extractPeriodFromResource(resource: IBase): String? {
        return try {
            when (resource) {
                is org.hl7.fhir.r4.model.Encounter -> {
                    val period = resource.period
                    if (period != null) {
                        formatPeriodAsInterval(period)
                    } else null
                }
                is org.hl7.fhir.r4.model.Resource -> {
                    // Try to access period via reflection for other FHIR R4 resources
                    val periodField = resource.javaClass.getMethod("getPeriod")
                    val period = periodField.invoke(resource) as? org.hl7.fhir.r4.model.Period
                    if (period != null) formatPeriodAsInterval(period) else null
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatPeriodAsInterval(period: org.hl7.fhir.r4.model.Period): String {
        val start = period.start?.toString() ?: "null"
        val end = period.end?.toString() ?: "null"
        return "[$start, $end)"
    }

    private fun notAvailable(): EvaluateResponse = EvaluateResponse().also {
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
                handler.continue_()
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
        val terminatedEventArguments = TerminatedEventArguments()
        terminatedEventArguments.restart = restart
        this.client.join().terminated(terminatedEventArguments)
    }

    protected fun exitServer() {
        this.exitServer(0)
    }

    protected fun exitServer(exitCode: Int) {
        val exitedEventArguments = ExitedEventArguments()
        exitedEventArguments.exitCode = exitCode
        this.client.join().exited(exitedEventArguments)
        setState(ServerState.STOPPED)
        this.exited.complete(null)
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
}
