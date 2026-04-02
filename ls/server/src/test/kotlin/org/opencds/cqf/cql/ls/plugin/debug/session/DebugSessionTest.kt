package org.opencds.cqf.cql.ls.plugin.debug.session

import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.plugin.debug.client.TestDebugClient
import java.net.Socket

class DebugSessionTest {

    // This test starts a Debug session on a background thread
    // which listens at a random socket. It creates a dummy client to
    // connect to that socket.
    @Test
    fun simpleSessionTest() {
        val session = DebugSession()

        // This starts a debug session on another thread
        val port: Int = session.start().join()
        val client = TestDebugClient()

        try {
            Socket("localhost", port).use { socket ->
                val launcher = DSPLauncher.createClientLauncher(
                    client, socket.getInputStream(), socket.getOutputStream()
                )
                val clientThread = launcher.startListening()
                val server: IDebugProtocolServer = launcher.remoteProxy
                server.initialize(InitializeRequestArguments()).get()
                server.configurationDone(ConfigurationDoneArguments()).get()
                server.disconnect(DisconnectArguments()).get()
                client.exited().get()
                clientThread.cancel(true)
            }
        } catch (e: Exception) {
            throw RuntimeException("error starting client", e)
        }

        assertNotNull(client.getServerOutput())
        assertEquals("got exited", client.getServerOutput())
    }
}
