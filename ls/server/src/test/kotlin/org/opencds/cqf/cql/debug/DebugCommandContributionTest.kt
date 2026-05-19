package org.opencds.cqf.cql.debug

import org.eclipse.lsp4j.ExecuteCommandParams
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.opencds.cqf.cql.debug.DebugCommandContribution.Companion.START_DEBUG_COMMAND
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import java.io.InputStream
import java.net.URI

class DebugCommandContributionTest {
    private lateinit var contribution: DebugCommandContribution

    @BeforeEach
    fun setUp() {
        val cs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ) = emptySet<URI>()

                override fun read(uri: URI): InputStream? = null
            }
        val cm = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
        contribution = DebugCommandContribution(cm, cs, IgContextManager(cs), LibraryResolutionManager(emptyList()))
    }

    @AfterEach
    fun tearDown() {
        // Release the ServerSocket.accept() that START_DEBUG_COMMAND leaves blocked
        // on a background thread; without this it would log noise 10s later.
        contribution.stop()
    }

    @Test
    fun `getCommands returns the start debug session command`() {
        assertEquals(setOf(START_DEBUG_COMMAND), contribution.getCommands())
    }

    @Test
    fun `executeCommand with unknown command throws IllegalArgumentException`() {
        val params = ExecuteCommandParams("org.unknown.command", emptyList())
        assertThrows<IllegalArgumentException> { contribution.executeCommand(params) }
    }

    @Test
    fun `executeCommand starts a new session and returns a positive port number`() {
        val params = ExecuteCommandParams(START_DEBUG_COMMAND, emptyList())
        val result = contribution.executeCommand(params).join()
        assertTrue(result is Int, "result should be a port number (Int)")
        assertTrue((result as Int) > 0, "port should be a positive integer")
    }

    @Test
    fun `executeCommand throws IllegalStateException when a session is already active`() {
        val params = ExecuteCommandParams(START_DEBUG_COMMAND, emptyList())
        contribution.executeCommand(params).join()
        assertThrows<IllegalStateException> { contribution.executeCommand(params) }
    }
}
