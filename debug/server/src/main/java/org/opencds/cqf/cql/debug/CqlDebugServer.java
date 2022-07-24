package org.opencds.cqf.cql.debug;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.ExitedEventArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.TerminatedEventArguments;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;

public class CqlDebugServer implements IDebugProtocolServer, IDebugProtocolClientAware {
    private CompletableFuture<Void> exited;
    private ServerState serverState;

    public CqlDebugServer() {
        this.exited = new CompletableFuture<>();
        this.serverState = ServerState.STARTED;
    }

    protected final CompletableFuture<IDebugProtocolClient> client = new CompletableFuture<>();

    @Override
    public void connect(IDebugProtocolClient client) {
        this.client.complete(client);
    }

    @Override
    public CompletableFuture<Capabilities> initialize(InitializeRequestArguments args) {
        checkState(ServerState.STARTED);
        // TODO: Work through all the capabilities we should support.
        Capabilities capabilities = new Capabilities();
        capabilities.setSupportsConfigurationDoneRequest(true);
        /// And... nothing else at this point.

        setState(ServerState.INITIALIZED);
        return CompletableFuture.completedFuture(capabilities)
                .whenCompleteAsync((c, e) -> this.client.join().initialized());
    }

    @Override
    public CompletableFuture<Void> configurationDone(ConfigurationDoneArguments args) {
        checkState(ServerState.INITIALIZED);
        setState(ServerState.CONFIGURED);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> disconnect(DisconnectArguments args) {
        return CompletableFuture.runAsync(() -> {
        }).whenCompleteAsync((o, e) -> this.exitServer());
    }

    @Override
    public CompletableFuture<Void> launch(Map<String, Object> args) {
        checkState(ServerState.CONFIGURED);
        setState(ServerState.RUNNING);

        // Create CQL request arguments..
        return CompletableFuture.runAsync(() -> {
        }).thenRunAsync(() -> {
            this.terminateServer();
            this.exitServer();
        });
    }

    protected void terminateServer() {
        this.terminateServer(null);
    }

    protected void terminateServer(Object restart) {
        TerminatedEventArguments terminatedEventArguments = new TerminatedEventArguments();
        terminatedEventArguments.setRestart(restart);
        this.client.join().terminated(terminatedEventArguments);
    }

    protected void exitServer() {
        this.exitServer(0);
    }

    protected void exitServer(int exitCode) {
        ExitedEventArguments exitedEventArguments = new ExitedEventArguments();
        exitedEventArguments.setExitCode(exitCode);
        this.client.join().exited(exitedEventArguments);
        setState(ServerState.STOPPED);
        this.exited.complete(null);
    }

    public CompletableFuture<Void> exited() {
        return this.exited;
    }

    protected void checkState(ServerState requiredState) {
        if (this.serverState != requiredState) {
            throw new IllegalStateException(
                    String.format("Operation required state %s, server actual state: %s",
                            requiredState.toString(), this.serverState.toString()));
        }
    }

    protected void setState(ServerState newState) {
        this.serverState = newState;
    }

    public ServerState getState() {
        return this.serverState;
    }
}
