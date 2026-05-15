package org.opencds.cqf.cql.debug

import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.debug.TestDebugClient
import java.net.Socket

class DebugSessionTest {
    @Test
    fun simpleSessionTest() {
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
        val session = DebugSession(debugServer)

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
}
