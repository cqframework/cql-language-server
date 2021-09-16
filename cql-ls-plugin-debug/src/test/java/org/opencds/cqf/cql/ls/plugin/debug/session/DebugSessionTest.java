package org.opencds.cqf.cql.ls.plugin.debug.session;

import java.net.Socket;
import java.util.concurrent.Future;

import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.opencds.cqf.cql.ls.plugin.debug.client.TestDebugClient;

import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class DebugSessionTest {

    // This test starts a Debug session on a background thread
    // which listens at a random socket. It creates a dummy client to 
    // connect to that socket.
    @Test
    public void simpleSessionTest() throws Exception {


        DebugSession session = new DebugSession();

        // This starts a debug session on another thread
        Integer port = session.start().join();
        TestDebugClient client = new TestDebugClient();


        try (Socket socket = new Socket("localhost", port)) {

            Launcher<IDebugProtocolServer> launcher = DSPLauncher.createClientLauncher(client, socket.getInputStream(),
                    socket.getOutputStream());
            Future<Void> clientThread = launcher.startListening();
            IDebugProtocolServer server = launcher.getRemoteProxy();
            server.initialize(new InitializeRequestArguments()).get();
            server.configurationDone(new ConfigurationDoneArguments()).get();
            server.disconnect(new DisconnectArguments()).get();
            client.exited().get();
            clientThread.cancel(true);   
        } catch (Exception e) {
            throw new RuntimeException("error starting client", e);
        }

        assertNotNull(client.getServerOutput());
        assertEquals("got exited", client.getServerOutput());
    }
}
