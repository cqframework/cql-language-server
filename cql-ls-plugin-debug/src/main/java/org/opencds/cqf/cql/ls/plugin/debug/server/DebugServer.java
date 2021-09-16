package org.opencds.cqf.cql.ls.plugin.debug.server;

import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.ExitedEventArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;

public class DebugServer implements IDebugProtocolServer, IDebugProtocolClientAware {
    private CompletableFuture<Void> exited;

    public DebugServer() {
        this.exited = new CompletableFuture<>();
    }

    protected final CompletableFuture<IDebugProtocolClient> client = new CompletableFuture<>();
    @Override
    public void connect(IDebugProtocolClient client) {
        this.client.complete(client);
    }

    @Override
    public CompletableFuture<Capabilities> initialize(InitializeRequestArguments args) {
        return CompletableFuture.completedFuture(new Capabilities());
    }

    @Override
    public CompletableFuture<Void> configurationDone(ConfigurationDoneArguments args) {
		return CompletableFuture.completedFuture(null);
	}

    @Override
	public CompletableFuture<Void> disconnect(DisconnectArguments args) {
        this.exit();
        return CompletableFuture.completedFuture(null);
	}

    public void exit() {
        this.client.join().exited(new ExitedEventArguments());
        this.exited.complete(null);
    }

    public CompletableFuture<Void> exited() {
        return this.exited;
    }

}
