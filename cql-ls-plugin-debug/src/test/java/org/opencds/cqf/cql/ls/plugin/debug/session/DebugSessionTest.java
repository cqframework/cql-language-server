package org.opencds.cqf.cql.ls.plugin.debug.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.net.Socket;

import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.opencds.cqf.cql.ls.plugin.debug.client.TestDebugClient;
import org.opencds.cqf.cql.ls.plugin.debug.server.TestDebugServer;
import org.testng.annotations.Test;

public class DebugSessionTest {

    @Test
    public void simpleSessionTest() {
        DebugSession session = new DebugSession(new TestDebugServer());

        Integer port = session.start().join();

        Object waitLock = new Object();
        TestDebugClient client = new TestDebugClient(waitLock);

        try (Socket socket = new Socket("localhost", port)) {

            Launcher<IDebugProtocolServer> launcher = DSPLauncher.createClientLauncher(client, socket.getInputStream(),
                    socket.getOutputStream());
            client.connect(launcher.getRemoteProxy());

            launcher.startListening();

            client.sendInitialize();

            synchronized (waitLock) {
                waitLock.wait();
            }
    
        } catch (Exception e) {
            throw new RuntimeException("error starting client", e);
        }

        assertNotNull(client.getServerOutput());
        assertEquals("got initialize", client.getServerOutput());
    }
}
