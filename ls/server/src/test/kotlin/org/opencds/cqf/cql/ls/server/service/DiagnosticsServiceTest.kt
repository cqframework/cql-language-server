package org.opencds.cqf.cql.ls.server.service

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent
import org.opencds.cqf.cql.ls.server.event.DidCloseTextDocumentEvent
import org.opencds.cqf.cql.ls.server.event.DidOpenTextDocumentEvent
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DiagnosticsServiceTest {
    companion object {
        private lateinit var diagnosticsService: DiagnosticsService

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val cs = TestContentService()
            val compilationManager = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs))
            diagnosticsService =
                DiagnosticsService(
                    CompletableFuture.completedFuture(Mockito.mock(LanguageClient::class.java)),
                    compilationManager,
                    cs,
                )
        }

        /** Builds a fresh DiagnosticsService backed by a Mockito LanguageClient. */
        private fun buildService(client: LanguageClient = Mockito.mock(LanguageClient::class.java)): DiagnosticsService {
            val cs = TestContentService()
            val compilationManager = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs))
            return DiagnosticsService(CompletableFuture.completedFuture(client), compilationManager, cs)
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

    // -------------------------------------------------------------------------
    // lint() — compile == null path (URI not in classpath → no CQL content)
    //
    // TestContentService.read() calls getResourceAsStream(uri.toString()), which
    // returns null for any path that is not on the classpath. A null stream causes
    // CqlCompilationManager.compile() to return null, triggering the
    // "Library does not contain CQL content." warning branch in lint().
    // -------------------------------------------------------------------------

    @Test
    fun lint_nonExistentUri_warningMessageIsNoCqlContent() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/NonExistent.cql")!!
        val d = diagnosticsService.lint(uri)[uri]!!.iterator().next()
        assertEquals("Library does not contain CQL content.", d.message)
    }

    @Test
    fun lint_nonExistentUri_warningRangeIsZeroZero() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/NonExistent.cql")!!
        val d = diagnosticsService.lint(uri)[uri]!!.iterator().next()
        assertEquals(Range(Position(0, 0), Position(0, 0)), d.range)
    }

    @Test
    fun lint_nonExistentUri_warningSourceIsLint() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/NonExistent.cql")!!
        val d = diagnosticsService.lint(uri)[uri]!!.iterator().next()
        assertEquals("lint", d.source)
    }

    @Test
    fun lint_nonExistentUri_warningIsWarning() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/NonExistent.cql")!!
        val d = diagnosticsService.lint(uri)[uri]!!.iterator().next()
        assertEquals(DiagnosticSeverity.Warning, d.severity)
    }

    // -------------------------------------------------------------------------
    // lint() — source field: only the null-compiler path sets it explicitly.
    // Diagnostics.convert() (used for real compiler exceptions) does NOT set source.
    // -------------------------------------------------------------------------

    @Test
    fun lint_syntaxError_diagnosticSourceIsNull() {
        // Diagnostics.convert() sets severity, range, and message but leaves source null.
        // Only the "Library does not contain CQL content." warning path sets source="lint".
        val uri: URI = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/SyntaxError.cql")!!
        val d: Diagnostic = diagnosticsService.lint(uri)[uri]!!.iterator().next()
        assertNull(d.source)
    }

    // -------------------------------------------------------------------------
    // Event handlers — didClose, didOpen
    // -------------------------------------------------------------------------

    @Test
    fun didClose_publishesEmptyDiagnosticsForTheClosedUri() {
        val mockClient = Mockito.mock(LanguageClient::class.java)
        val svc = buildService(mockClient)

        val closeParams = DidCloseTextDocumentParams()
        closeParams.textDocument = TextDocumentIdentifier("file:///workspace/One.cql")
        svc.didClose(DidCloseTextDocumentEvent(closeParams))

        val captor = ArgumentCaptor.forClass(PublishDiagnosticsParams::class.java)
        Mockito.verify(mockClient).publishDiagnostics(captor.capture())
        assertEquals("file:///workspace/One.cql", captor.value.uri)
        assertTrue(captor.value.diagnostics.isEmpty())
    }

    @Test
    fun didOpen_publishesDiagnosticsToClient() {
        val mockClient = Mockito.mock(LanguageClient::class.java)
        val svc = buildService(mockClient)

        val item = TextDocumentItem()
        item.uri = "/org/opencds/cqf/cql/ls/server/One.cql"
        val openParams = DidOpenTextDocumentParams()
        openParams.textDocument = item
        svc.didOpen(DidOpenTextDocumentEvent(openParams))

        // doLint() calls publishDiagnostics synchronously — verify it was called at least once.
        Mockito.verify(mockClient, Mockito.atLeastOnce()).publishDiagnostics(Mockito.any())
    }

    // -------------------------------------------------------------------------
    // debounce() — task execution and cancellation
    // -------------------------------------------------------------------------

    @Test
    fun didChangeWatchedFiles_publishesDiagnosticsForCqlFiles() {
        val mockClient = Mockito.mock(LanguageClient::class.java)
        val svc = buildService(mockClient)

        val fileEvent = FileEvent("/org/opencds/cqf/cql/ls/server/One.cql", FileChangeType.Changed)
        val params = DidChangeWatchedFilesParams(listOf(fileEvent))
        svc.didChangeWatchedFiles(DidChangeWatchedFilesEvent(params))

        Mockito.verify(mockClient, Mockito.atLeastOnce()).publishDiagnostics(Mockito.any())
    }

    @Test
    fun didChangeWatchedFiles_ignoresNonCqlFiles() {
        val mockClient = Mockito.mock(LanguageClient::class.java)
        val svc = buildService(mockClient)

        val fileEvent = FileEvent("/some/path/options.json", FileChangeType.Changed)
        val params = DidChangeWatchedFilesParams(listOf(fileEvent))
        svc.didChangeWatchedFiles(DidChangeWatchedFilesEvent(params))

        Mockito.verify(mockClient, Mockito.never()).publishDiagnostics(Mockito.any())
    }

    // -------------------------------------------------------------------------
    // debounce() — task execution and cancellation
    // -------------------------------------------------------------------------

    @Test
    fun debounce_taskExecutesAfterDelay() {
        val svc = buildService()
        val latch = CountDownLatch(1)
        svc.debounce(50L) { latch.countDown() }
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Debounced task should execute within timeout")
    }

    @Test
    fun debounce_cancelsPreviousTaskWhenNewOneArrives() {
        val svc = buildService()
        val firstExecuted = AtomicBoolean(false)
        val secondLatch = CountDownLatch(1)

        svc.debounce(500L) { firstExecuted.set(true) } // long delay — will be cancelled
        svc.debounce(50L) { secondLatch.countDown() } // short delay — cancels the first

        assertTrue(secondLatch.await(2, TimeUnit.SECONDS), "Second task should execute")
        assertFalse(firstExecuted.get(), "First task should have been cancelled before it ran")
    }
}
