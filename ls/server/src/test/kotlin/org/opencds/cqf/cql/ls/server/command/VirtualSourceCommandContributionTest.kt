package org.opencds.cqf.cql.ls.server.command

import com.google.gson.JsonParser
import org.eclipse.lsp4j.ExecuteCommandParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService
import java.net.URI

class VirtualSourceCommandContributionTest {
    companion object {
        private lateinit var compilationManager: CqlCompilationManager
        private lateinit var contribution: VirtualSourceCommandContribution

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            val cs = TestContentService()
            compilationManager =
                CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
            contribution = VirtualSourceCommandContribution(compilationManager)
        }
    }

    @Test
    fun getCommands() {
        assertEquals(setOf("org.opencds.cqf.cql.ls.virtualSource"), contribution.getCommands())
    }

    @Test
    fun `returns cached source text for a previously-compiled virtual URI`() {
        val virtualUri = URI.create("cql-virtual:///One-1.0.0.cql")
        val cqlText = "library One version '1.0.0'\n\ndefine \"X\": 1\n"
        compilationManager.compile(virtualUri, cqlText.byteInputStream())

        val params =
            ExecuteCommandParams().also {
                it.command = "org.opencds.cqf.cql.ls.virtualSource"
                it.arguments = listOf(JsonParser.parseString("\"$virtualUri\""))
            }

        val result = contribution.executeCommand(params).get()

        assertEquals(cqlText, result)
    }

    @Test
    fun `returns null for a URI that was never compiled`() {
        val params =
            ExecuteCommandParams().also {
                it.command = "org.opencds.cqf.cql.ls.virtualSource"
                it.arguments = listOf(JsonParser.parseString("\"cql-virtual:///Unknown-1.0.0.cql\""))
            }

        val result = contribution.executeCommand(params).get()

        assertNull(result)
    }

    @Test
    fun `returns null when no arguments are provided`() {
        val params =
            ExecuteCommandParams().also {
                it.command = "org.opencds.cqf.cql.ls.virtualSource"
                it.arguments = emptyList()
            }

        val result = contribution.executeCommand(params).get()

        assertNull(result)
    }
}
