package org.opencds.cqf.cql.ls.plugin.debug

import org.eclipse.lsp4j.ExecuteCommandParams
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.plugin.debug.DebugCommandContribution.Companion.START_DEBUG_COMMAND
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import java.io.InputStream
import java.net.URI

class DebugCommandContributionTest {

    private lateinit var contribution: DebugCommandContribution

    @BeforeEach
    fun setUp() {
        // cqlCompilationManager is injected but not referenced inside executeCommand;
        // construct it with a no-op ContentService to keep the build lightweight.
        val cs = object : ContentService {
            override fun locate(root: URI, identifier: VersionedIdentifier) = emptySet<URI>()
            override fun read(uri: URI): InputStream? = null
        }
        val cm = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs))
        contribution = DebugCommandContribution(cm)
    }

    // -------------------------------------------------------------------------
    // Command registration
    // -------------------------------------------------------------------------

    @Test
    fun `getCommands returns the start debug session command`() {
        assertEquals(setOf(START_DEBUG_COMMAND), contribution.getCommands())
    }

    // -------------------------------------------------------------------------
    // Dispatch — unknown command
    // -------------------------------------------------------------------------

    @Test
    fun `executeCommand with unknown command throws IllegalArgumentException`() {
        val params = ExecuteCommandParams("org.unknown.command", emptyList())
        // DebugCommandContribution throws IllegalArgumentException (not RuntimeException)
        // for unrecognised commands, unlike the CommandContribution interface default.
        assertThrows<IllegalArgumentException> { contribution.executeCommand(params) }
    }

    // -------------------------------------------------------------------------
    // Start debug session — happy path
    // -------------------------------------------------------------------------

    @Test
    fun `executeCommand starts a new session and returns a positive port number`() {
        val params = ExecuteCommandParams(START_DEBUG_COMMAND, emptyList())
        // executeCommand blocks until DebugSession.start() resolves (ServerSocket bound).
        val result = contribution.executeCommand(params).join()
        assertTrue(result is Int, "result should be a port number (Int)")
        assertTrue((result as Int) > 0, "port should be a positive integer")
    }

    // -------------------------------------------------------------------------
    // Start debug session — already active
    // -------------------------------------------------------------------------

    @Test
    fun `executeCommand throws IllegalStateException when a session is already active`() {
        val params = ExecuteCommandParams(START_DEBUG_COMMAND, emptyList())
        // First call: starts a new session and waits for the port to be assigned.
        // After this returns, isActive() == true (flag is set before socket starts).
        contribution.executeCommand(params).join()
        // Second call: finds session != null && session.isActive() → throws
        assertThrows<IllegalStateException> { contribution.executeCommand(params) }
    }
}
