package org.opencds.cqf.cql.debug

import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import java.net.Socket

class DebugSessionTest {
    private fun makeSession(): DebugSession {
        val cs =
            object : ContentService {
                override fun locate(
                    root: java.net.URI,
                    identifier: org.hl7.elm.r1.VersionedIdentifier,
                ) = emptySet<java.net.URI>()

                override fun read(uri: java.net.URI): java.io.InputStream? = null
            }
        val cm = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
        val debugServer = CqlDebugServer(cm, cs, IgContextManager(cs), LibraryResolutionManager(emptyList()))
        return DebugSession(debugServer)
    }

    @Test
    fun simpleSessionTest() {
        val session = makeSession()

        val port: Int = session.start().join()
        val client = TestDebugClient()

        try {
            Socket("localhost", port).use { socket ->
                val launcher =
                    DSPLauncher.createClientLauncher(
                        client,
                        socket.getInputStream(),
                        socket.getOutputStream(),
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

    @Test
    fun `stop releases an unconnected accept and clears isActive`() {
        val session = makeSession()
        session.start().join()
        assertTrue(session.isActive())

        session.stop()

        // Wait briefly for the listener thread to exit its catch block
        // and clear isActiveFlag. accept() unblocks immediately on close.
        val deadline = System.currentTimeMillis() + 2000
        while (session.isActive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertFalse(session.isActive(), "session should be inactive after stop()")
    }

    @Test
    fun `stop is idempotent and safe before start`() {
        val session = makeSession()
        session.stop() // before start — must not throw
        session.stop() // double stop — must not throw
        assertFalse(session.isActive())
    }

    @Test
    fun `isActive returns false before start`() {
        val session = makeSession()
        assertFalse(session.isActive())
    }

    @Test
    fun `external socket close causes IOException and inactive session`() {
        val session = makeSession()
        session.start().join()
        assertTrue(session.isActive())

        // Use reflection to close the underlying server socket without calling stop().
        // This triggers IOException in the accept() loop with stopped == false.
        val socketField = DebugSession::class.java.getDeclaredField("serverSocket")
        socketField.isAccessible = true
        val ss = socketField.get(session) as java.net.ServerSocket
        ss.close()

        val deadline = System.currentTimeMillis() + 3000
        while (session.isActive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertFalse(session.isActive(), "session should become inactive after socket is closed externally")
    }
}
