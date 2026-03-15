package org.opencds.cqf.cql.ls.server.service

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import java.net.URI
import java.util.concurrent.CompletableFuture

class DiagnosticsServiceTest {

    companion object {
        private lateinit var diagnosticsService: DiagnosticsService

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val cs = TestContentService()
            val compilationManager = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs))
            diagnosticsService = DiagnosticsService(
                CompletableFuture.completedFuture(Mockito.mock(LanguageClient::class.java)),
                compilationManager,
                cs
            )
        }
    }

    @Test
    fun missingInclude() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/MissingInclude.cql")!!
        val diagnostics = diagnosticsService.lint(uri)

        assertTrue(diagnostics.containsKey(uri))

        val dSet = diagnostics[uri]!!
        assertEquals(1, dSet.size)

        val d = dSet.iterator().next()
        assertEquals(d.range, Range(Position(2, 0), Position(2, 15)))
    }

    @Test
    fun validCql_noErrors() {
        val uri: URI = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/Two.cql")!!
        val diagnostics = diagnosticsService.lint(uri)

        assertTrue(diagnostics.containsKey(uri))
        assertTrue(diagnostics[uri]!!.isEmpty())
    }

    @Test
    fun syntaxError_returnsDiagnostic() {
        val uri: URI = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/SyntaxError.cql")!!
        val diagnostics = diagnosticsService.lint(uri)

        assertTrue(diagnostics.containsKey(uri))
        assertFalse(diagnostics[uri]!!.isEmpty())

        val d: Diagnostic = diagnostics[uri]!!.iterator().next()
        assertEquals(DiagnosticSeverity.Error, d.severity)
    }

    @Test
    fun oneCqlValid_noErrors() {
        // One.cql is a simple valid library with no includes; lint should produce no diagnostics.
        val uri: URI = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        val diagnostics = diagnosticsService.lint(uri)

        assertTrue(diagnostics.containsKey(uri))
        assertTrue(diagnostics[uri]!!.isEmpty())
    }

    @Test
    fun missingInclude_diagnosticHasNonNullMessage() {
        val uri: URI = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/MissingInclude.cql")!!
        val diagnostics = diagnosticsService.lint(uri)

        val dSet = diagnostics[uri]!!
        assertFalse(dSet.isEmpty())

        val d: Diagnostic = dSet.iterator().next()
        assertNotNull(d.message)
        assertFalse(d.message.isEmpty())
    }

    @Test
    fun missingInclude_diagnosticHasNonNullRange() {
        val uri: URI = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/MissingInclude.cql")!!
        val diagnostics = diagnosticsService.lint(uri)

        val d: Diagnostic = diagnostics[uri]!!.iterator().next()
        assertNotNull(d.range)
        assertNotNull(d.range.start)
        assertNotNull(d.range.end)
    }
}
