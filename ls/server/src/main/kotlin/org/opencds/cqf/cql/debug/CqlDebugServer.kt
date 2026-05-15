package org.opencds.cqf.cql.debug

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
import org.hl7.elm.r1.FunctionDef
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.command.CqlEvaluator
import org.opencds.cqf.cql.ls.server.command.ContextRequest
import org.opencds.cqf.cql.ls.server.command.ExecuteCqlRequest
import org.opencds.cqf.cql.ls.server.command.LibraryRequest
import org.opencds.cqf.cql.ls.server.command.ModelRequest
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture

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

    protected var snapshots: List<ExpressionSnapshot> = emptyList()
    protected var currentIndex: Int = -1
    protected val breakpointLines = mutableSetOf<Int>()

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
        return CompletableFuture.runAsync {}.whenCompleteAsync { _, _ -> this.exitServer() }
    }

    override fun setExceptionBreakpoints(args: SetExceptionBreakpointsArguments): CompletableFuture<SetExceptionBreakpointsResponse> =
        CompletableFuture.completedFuture(SetExceptionBreakpointsResponse())

    override fun setBreakpoints(args: SetBreakpointsArguments): CompletableFuture<SetBreakpointsResponse> {
        breakpointLines.clear()
        args.breakpoints?.forEach { breakpointLines.add(it.line - 1) }
        val bps = args.breakpoints?.map { bp ->
            Breakpoint().also { it.isVerified = true; it.line = bp.line }
        }?.toTypedArray() ?: emptyArray()
        return CompletableFuture.completedFuture(SetBreakpointsResponse().also { it.breakpoints = bps })
    }

    override fun launch(args: Map<String, Any>): CompletableFuture<Void> {
        return configuredFuture.thenCompose {
            checkState(ServerState.CONFIGURED)
            setState(ServerState.RUNNING)
            CompletableFuture.runAsync {
                try {
                    executeLaunch(Gson().fromJson(Gson().toJson(args), DebugLaunchArgs::class.java))
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

        val request = buildExecuteCqlRequest(args)
        val response = CqlEvaluator.evaluate(request, contentService, igContextManager, libraryResolutionManager)
        val expressions = response.results.firstOrNull()?.expressions ?: emptyList()

        val compiler = compilationManager.compile(libraryUri)
        val locatorMap: Map<String, String?> = compiler?.compiledLibrary?.library
            ?.statements?.def
            ?.filterNot { it is FunctionDef }
            ?.filter { it.name != null }
            ?.associate { it.name!! to it.locator }
            ?: emptyMap()

        snapshots = expressions.mapNotNull { expr ->
            parseLocator(locatorMap[expr.name] ?: return@mapNotNull null, expr.name, expr.value, args.libraryUri)
        }.sortedBy { it.startLine }

        currentIndex = -1
        stepToNext("entry")
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
                    parameters = emptyList(),
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
        val snap = snapshots.getOrNull(currentIndex)
        val frame = StackFrame().also { f ->
            f.id = currentIndex.coerceAtLeast(0)
            f.name = snap?.name ?: "(none)"
            f.line = (snap?.startLine ?: 0) + 1
            f.column = (snap?.startChar ?: 0) + 1
            f.endLine = (snap?.endLine ?: 0) + 1
            f.endColumn = (snap?.endChar ?: 0)
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

    override fun scopes(args: ScopesArguments): CompletableFuture<ScopesResponse> =
        CompletableFuture.completedFuture(
            ScopesResponse().also {
                it.scopes = arrayOf(
                    Scope().also { s ->
                        s.name = "Expressions"
                        s.variablesReference = 1
                        s.isExpensive = false
                    },
                )
            },
        )

    override fun variables(args: VariablesArguments): CompletableFuture<VariablesResponse> {
        val vars = snapshots.take(currentIndex + 1).map { snap ->
            Variable().also {
                it.name = snap.name
                it.value = snap.value
                it.variablesReference = 0
            }
        }.toTypedArray()
        return CompletableFuture.completedFuture(VariablesResponse().also { it.variables = vars })
    }

    override fun evaluate(args: EvaluateArguments): CompletableFuture<EvaluateResponse> =
        CompletableFuture.supplyAsync {
            when (args.context) {
                "hover" -> handleHoverEvaluate(args.expression, args.frameId)
                else -> EvaluateResponse().also {
                    it.result = "N/A"
                    it.variablesReference = 0
                }
            }
        }

    private fun handleHoverEvaluate(expression: String, frameId: Int?): EvaluateResponse {
        val snapshot = if (frameId != null && frameId in snapshots.indices) {
            snapshots.subList(0, frameId + 1).lastOrNull { it.name == expression }
        } else {
            snapshots.lastOrNull { it.name == expression }
        }
        return EvaluateResponse().also {
            it.result = snapshot?.value ?: "not available"
            it.variablesReference = 0
        }
    }

    override fun next(args: NextArguments): CompletableFuture<Void> =
        CompletableFuture.runAsync { stepToNext() }

    override fun stepIn(args: StepInArguments): CompletableFuture<Void> =
        CompletableFuture.runAsync { stepToNext() }

    override fun stepOut(args: StepOutArguments): CompletableFuture<Void> =
        CompletableFuture.runAsync { stepToNext() }

    override fun continue_(args: ContinueArguments): CompletableFuture<ContinueResponse> =
        CompletableFuture.supplyAsync {
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
