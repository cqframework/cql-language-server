package org.opencds.cqf.cql.ls.plugin.debug.server;

import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;

public class DebugServer implements IDebugProtocolServer, IDebugProtocolClientAware {

    protected final CompletableFuture<IDebugProtocolClient> client = new CompletableFuture<>();
    @Override
    public void connect(IDebugProtocolClient client) {
        this.client.complete(client);
    }
}
