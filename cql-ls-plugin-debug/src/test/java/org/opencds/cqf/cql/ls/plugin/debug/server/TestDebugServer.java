package org.opencds.cqf.cql.ls.plugin.debug.server;

import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;

public class TestDebugServer extends DebugServer {
    
    @Override
	public CompletableFuture<Capabilities> initialize(InitializeRequestArguments args) {
        OutputEventArguments oea = new OutputEventArguments();
        oea.setOutput("got initialize");
		this.client.join().output(oea);
        return CompletableFuture.completedFuture(new Capabilities());
	}
    
}
