package org.opencds.cqf.cql.ls.plugin.debug.server;

import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.opencds.cqf.cql.ls.plugin.debug.client.TestDebugClient;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class DebugServerTest {

    // This is an initial test to work through the client - server handshakes.
    // A more sophisticated version would wait for each step to complete, and for callbacks to happen.
    @Test
    public void simpleServerTest() throws Exception {

        DebugServer server = new DebugServer();
        TestDebugClient client = new TestDebugClient();

        server.connect(client);

        server.initialize(new InitializeRequestArguments()).get();
        server.configurationDone(new ConfigurationDoneArguments()).get();
        server.disconnect(new DisconnectArguments()).get();

        assertNotNull(client.getServerOutput());
        assertEquals("got exited", client.getServerOutput());
    }
    
}
