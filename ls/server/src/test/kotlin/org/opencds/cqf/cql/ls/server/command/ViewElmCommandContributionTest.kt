package org.opencds.cqf.cql.ls.server.command

import com.google.gson.JsonParser
import org.eclipse.lsp4j.ExecuteCommandParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture

class ViewElmCommandContributionTest {
    companion object {
        private lateinit var viewElmCommandContribution: ViewElmCommandContribution

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val cs = TestContentService()
            val cqlCompilationManager =
                CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
            viewElmCommandContribution = ViewElmCommandContribution(cqlCompilationManager)
        }
    }

    @Test
    fun getCommands() {
        assertEquals(1, viewElmCommandContribution.getCommands().size)
        assertEquals(
            "org.opencds.cqf.cql.ls.viewElm",
            viewElmCommandContribution.getCommands().toTypedArray()[0],
        )
    }

    @Test
    fun executeCommand() {
        val params = ExecuteCommandParams()
        params.command = "org.opencds.cqf.cql.ls.viewElm"
        params.arguments =
            listOf(
                JsonParser.parseString("\"\\/org\\/opencds\\/cqf\\/cql\\/ls\\/server\\/One.cql\""),
            )
        val future: CompletableFuture<Void> =
            viewElmCommandContribution
                .executeCommand(params)
                .thenAccept { result ->
                    try {
                        val expectedXml =
                            String(
                                Files.readAllBytes(
                                    Paths.get("src/test/resources/org/opencds/cqf/cql/ls/server/One.xml"),
                                ),
                            )
                                .trim()
                                .replace("\\s+".toRegex(), "")
                        assertEquals(expectedXml, result.toString().trim().replace("\\s+".toRegex(), ""))
                    } catch (e: IOException) {
                        throw RuntimeException(e)
                    }
                }

        // This ensures the test waits and fails if an exception occurs
        future.join()
    }

    @Test
    fun executeCommandWithXmlElmType() {
        val params = ExecuteCommandParams()
        params.command = "org.opencds.cqf.cql.ls.viewElm"
        params.arguments =
            listOf(
                JsonParser.parseString("\"\\/org\\/opencds\\/cqf\\/cql\\/ls\\/server\\/One.cql\""),
                JsonParser.parseString("\"xml\""),
            )
        val future: CompletableFuture<Void> =
            viewElmCommandContribution
                .executeCommand(params)
                .thenAccept { result ->
                    try {
                        val expectedXml =
                            String(
                                Files.readAllBytes(
                                    Paths.get("src/test/resources/org/opencds/cqf/cql/ls/server/One.xml"),
                                ),
                            )
                                .trim()
                                .replace("\\s+".toRegex(), "")
                        assertEquals(expectedXml, result.toString().trim().replace("\\s+".toRegex(), ""))
                    } catch (e: IOException) {
                        throw RuntimeException(e)
                    }
                }

        // This ensures the test waits and fails if an exception occurs
        future.join()
    }

    @Test
    fun executeCommandWithJsonElmType() {
        val params = ExecuteCommandParams()
        params.command = "org.opencds.cqf.cql.ls.viewElm"
        params.arguments =
            listOf(
                JsonParser.parseString("\"\\/org\\/opencds\\/cqf\\/cql\\/ls\\/server\\/One.cql\""),
                JsonParser.parseString("\"json\""),
            )
        val future: CompletableFuture<Void> =
            viewElmCommandContribution
                .executeCommand(params)
                .thenAccept { result ->
                    try {
                        val expectedJson =
                            String(
                                Files.readAllBytes(
                                    Paths.get("src/test/resources/org/opencds/cqf/cql/ls/server/One.json"),
                                ),
                            )
                                .trim()
                                .replace("\\s+".toRegex(), "")
                        assertEquals(expectedJson, result.toString().trim().replace("\\s+".toRegex(), ""))
                    } catch (e: IOException) {
                        throw RuntimeException(e)
                    }
                }

        // This ensures the test waits and fails if an exception occurs
        future.join()
    }

    // -----------------------------------------------------------------------
    // Early-return / null guard paths
    // -----------------------------------------------------------------------

    @Test
    fun executeCommand_nullArgs_returnsNull() {
        val params = ExecuteCommandParams()
        params.command = "org.opencds.cqf.cql.ls.viewElm"
        params.arguments = null
        assertNull(viewElmCommandContribution.executeCommand(params).join())
    }

    @Test
    fun executeCommand_emptyArgs_returnsNull() {
        val params = ExecuteCommandParams()
        params.command = "org.opencds.cqf.cql.ls.viewElm"
        params.arguments = emptyList()
        assertNull(viewElmCommandContribution.executeCommand(params).join())
    }

    @Test
    fun executeCommand_invalidUri_returnsNull() {
        // URI(String) throws on unencoded spaces, so parseOrNull returns null
        val params = ExecuteCommandParams()
        params.command = "org.opencds.cqf.cql.ls.viewElm"
        params.arguments = listOf(JsonParser.parseString("\"not a valid uri\""))
        assertNull(viewElmCommandContribution.executeCommand(params).join())
    }

    @Test
    fun executeCommand_compilerReturnsNull_returnsNull() {
        // TestContentService returns null for any URI not on the classpath, so compile() returns null
        val params = ExecuteCommandParams()
        params.command = "org.opencds.cqf.cql.ls.viewElm"
        params.arguments = listOf(JsonParser.parseString("\"file:///nonexistent/Missing.cql\""))
        assertNull(viewElmCommandContribution.executeCommand(params).join())
    }

    // -----------------------------------------------------------------------
    // Unknown command — falls through to interface default which throws
    // -----------------------------------------------------------------------

    @Test
    fun executeCommand_unknownCommand_throwsRuntimeException() {
        val params = ExecuteCommandParams()
        params.command = "org.opencds.cqf.cql.ls.unknownCommand"
        assertThrows<RuntimeException> { viewElmCommandContribution.executeCommand(params) }
    }
}
