package org.opencds.cqf.cql.debug

import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.ExitedEventArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.TerminatedEventArguments
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import java.util.concurrent.CompletableFuture

open class CqlDebugServer : IDebugProtocolServer, IDebugProtocolClientAware {
    private var exited: CompletableFuture<Void> = CompletableFuture()
    private var serverState: ServerState = ServerState.STARTED

    protected val client: CompletableFuture<IDebugProtocolClient> = CompletableFuture()

    override fun connect(client: IDebugProtocolClient) {
        this.client.complete(client)
    }

    override fun initialize(args: InitializeRequestArguments): CompletableFuture<Capabilities> {
        checkState(ServerState.STARTED)
        // TODO: Work through all the capabilities we should support.
        val capabilities = Capabilities()
        capabilities.supportsConfigurationDoneRequest = true
        /// And... nothing else at this point.

        setState(ServerState.INITIALIZED)
        return CompletableFuture.completedFuture(capabilities)
            .whenCompleteAsync { _, _ -> this.client.join().initialized() }
    }

    override fun configurationDone(args: ConfigurationDoneArguments): CompletableFuture<Void> {
        checkState(ServerState.INITIALIZED)
        setState(ServerState.CONFIGURED)
        return CompletableFuture.completedFuture(null)
    }

    override fun disconnect(args: DisconnectArguments): CompletableFuture<Void> {
        return CompletableFuture.runAsync {}.whenCompleteAsync { _, _ -> this.exitServer() }
    }

    override fun launch(args: Map<String, Any>): CompletableFuture<Void> {
        checkState(ServerState.CONFIGURED)
        setState(ServerState.RUNNING)

        // Create CQL request arguments..
        return CompletableFuture.runAsync {}.thenRunAsync {
            this.terminateServer()
            this.exitServer()
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
                "Operation required state ${requiredState}, server actual state: ${this.serverState}"
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
