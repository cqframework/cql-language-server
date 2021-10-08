package org.opencds.cqf.cql.ls.plugin.debug.client;

import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.debug.ExitedEventArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;

public class TestDebugClient implements IDebugProtocolClient {
    private String serverOutput = null;

    private CompletableFuture<Void> exited;
    public TestDebugClient() {
        this.exited = new CompletableFuture<>();
    }

    public String getServerOutput() {
        return this.serverOutput;
    }

    @Override
    public void initialized() {
    }

    @Override
	public void output(OutputEventArguments args) {
        // this.serverOutput = args.getOutput();

	}

    @Override
    public void exited(ExitedEventArguments args){
        this.serverOutput = "got exited";
        this.exited.complete(null);
    }

    public CompletableFuture<Void> exited() {
        return this.exited;
    }
}
