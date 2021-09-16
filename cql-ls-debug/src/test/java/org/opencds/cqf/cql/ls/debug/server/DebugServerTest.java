package org.opencds.cqf.cql.ls.debug.server;

import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.ExitedEventArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.testng.annotations.Test;

import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;

public class DebugServerTest {

    // This is an initial test to work through the client - server handshakes.
    // A more sophisticated version would wait for each step to complete, and for callbacks to happen.
    @Test
    public void simpleServerTest() throws Exception {

        DebugServer server = new DebugServer();
        IDebugProtocolClient client = Mockito.mock(IDebugProtocolClient.class);

        server.connect(client);

        server.initialize(new InitializeRequestArguments()).get();
        server.configurationDone(new ConfigurationDoneArguments()).get();
        server.disconnect(new DisconnectArguments()).get();

        Mockito.verify(client).exited(any(ExitedEventArguments.class));
    }
    
}
