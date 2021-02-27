package org.opencds.cqf.cql.ls.plugin.debug.client;

import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;

public class TestDebugClient implements IDebugProtocolClient {

    private Object waitLock;

    private String serverOutput = null;

    public TestDebugClient(Object waitLock) {
        this.waitLock = waitLock;
    }

    public String getServerOutput() {
        return this.serverOutput;
    }

    private CompletableFuture<IDebugProtocolServer> server = new CompletableFuture<>();

    public void connect(IDebugProtocolServer server) {
        this.server.complete(server);
    } 
    
    public void sendInitialize() {
        this.server.join().initialize(new InitializeRequestArguments());
    }

    @Override
	public void output(OutputEventArguments args) {
        this.serverOutput = args.getOutput();

        synchronized (this.waitLock) {
            this.waitLock.notifyAll();
        }
	}
}
