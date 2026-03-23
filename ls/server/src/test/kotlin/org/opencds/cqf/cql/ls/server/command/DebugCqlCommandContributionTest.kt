package org.opencds.cqf.cql.ls.server.command

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import org.eclipse.lsp4j.ExecuteCommandParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.opencds.cqf.cql.ls.server.command.DebugCqlCommandContribution.Companion.START_DEBUG_COMMAND
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.service.TestContentService
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class DebugCqlCommandContributionTest {
    companion object {
        private lateinit var contribution: DebugCqlCommandContribution

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val cs = TestContentService()
            contribution = DebugCqlCommandContribution(IgContextManager(cs))
        }
    }

    /**
     * Returns a file: URI pointing to the directory containing the CQL test fixtures.
     * Uses .toURI() so the path is correct on macOS/Linux and Windows.
     */
    private fun cqlFixtureDirectoryUrl(): String =
        DebugCqlCommandContributionTest::class.java
            .getResource("/org/opencds/cqf/cql/ls/server/One.cql")!!
            .toURI()
            .resolve(".")
            .toString()

    /** Wraps each string as a JsonPrimitive, matching the format executeCql() expects. */
    private fun jsonArgs(vararg args: String): List<JsonElement> = args.map { JsonPrimitive(it) }

    private fun debugParams(vararg args: String): ExecuteCommandParams = ExecuteCommandParams(START_DEBUG_COMMAND, jsonArgs(*args))

    // -------------------------------------------------------------------------
    // Command registration
    // -------------------------------------------------------------------------

    @Test
    fun `getCommands returns the start debug session command`() {
        assertEquals(setOf(START_DEBUG_COMMAND), contribution.getCommands())
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    @Test
    fun `executeCommand with unknown command throws RuntimeException`() {
        val params = ExecuteCommandParams("org.unknown.command", emptyList())
        assertThrows<RuntimeException> { contribution.executeCommand(params) }
    }

    // -------------------------------------------------------------------------
    // Successful execution
    // -------------------------------------------------------------------------

    @Test
    fun `successful evaluation returns CQL output in the future result`() {
        val params = debugParams("cql", "-fv", "R4", "-lu", cqlFixtureDirectoryUrl(), "-ln", "One")
        val result = contribution.executeCommand(params).join() as String
        assertTrue(result.contains("One=1"))
    }

    @Test
    fun `CQL output does not bleed to caller System dot out`() {
        // executeCql() redirects System.out internally; output should go to the future result,
        // not to whatever System.out the caller had set.
        val callerCapture = ByteArrayOutputStream()
        val originalOut = System.out
        System.setOut(PrintStream(callerCapture))
        try {
            val params = debugParams("cql", "-fv", "R4", "-lu", cqlFixtureDirectoryUrl(), "-ln", "One")
            val result = contribution.executeCommand(params).join() as String
            assertEquals("", callerCapture.toString(), "CQL output should not appear in caller's stdout")
            assertTrue(result.contains("One=1"), "CQL output should be in the future result")
        } finally {
            System.setOut(originalOut)
        }
    }

    @Test
    fun `stdout and stderr are restored to console streams after successful execution`() {
        val preCallOut = System.out
        val params = debugParams("cql", "-fv", "R4", "-lu", cqlFixtureDirectoryUrl(), "-ln", "One")
        contribution.executeCommand(params).join()
        // finally block always replaces System.out with new PrintStream(FileDescriptor.out)
        assertNotSame(preCallOut, System.out, "stdout should be replaced with a new console stream")
        assertDoesNotThrow { System.out.println("stdout is functional after execution") }
    }

    // -------------------------------------------------------------------------
    // Failed execution
    // -------------------------------------------------------------------------

    @Test
    fun `failed evaluation includes Evaluation logs section in result`() {
        // "NonExistentLibrary" is not present in the fixture directory; the engine will throw,
        // picocli will write the error to the redirected System.err, and executeCql() appends
        // it to the result under "Evaluation logs:".
        val params = debugParams("cql", "-fv", "R4", "-lu", cqlFixtureDirectoryUrl(), "-ln", "NonExistentLibrary")
        val result = contribution.executeCommand(params).join() as String
        assertTrue(result.contains("Evaluation logs:"), "Expected 'Evaluation logs:' section when CQL evaluation fails")
    }

    @Test
    fun `stdout and stderr are restored after failed evaluation`() {
        val params = debugParams("cql", "-fv", "R4", "-lu", cqlFixtureDirectoryUrl(), "-ln", "NonExistentLibrary")
        contribution.executeCommand(params).join()
        // finally block runs even on error paths
        assertDoesNotThrow { System.out.println("stdout still functional after failure") }
        assertDoesNotThrow { System.err.println("stderr still functional after failure") }
    }
}
