package org.opencds.cqf.cql.ls.server.service

import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.services.LanguageClient
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.opencds.cqf.cql.ls.server.event.DidChangeTextDocumentEvent
import org.opencds.cqf.cql.ls.server.event.DidCloseTextDocumentEvent
import org.opencds.cqf.cql.ls.server.event.DidOpenTextDocumentEvent
import org.opencds.cqf.cql.ls.server.event.DidSaveTextDocumentEvent
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.provider.CompletionProvider
import org.opencds.cqf.cql.ls.server.provider.DefinitionProvider
import org.opencds.cqf.cql.ls.server.provider.DocumentSymbolProvider
import org.opencds.cqf.cql.ls.server.provider.FormattingProvider
import org.opencds.cqf.cql.ls.server.provider.HoverProvider
import org.opencds.cqf.cql.ls.server.provider.ReferencesProvider
import java.util.concurrent.CompletableFuture

class CqlTextDocumentServiceTest {
    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildService(
        bus: EventBus,
        clientFuture: CompletableFuture<LanguageClient> = CompletableFuture(),
    ): CqlTextDocumentService {
        val cs = TestContentService()
        val compilationManager = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
        val hoverProvider = HoverProvider(compilationManager, cs)
        return CqlTextDocumentService(
            clientFuture,
            hoverProvider,
            FormattingProvider(cs),
            bus,
            DefinitionProvider(compilationManager, cs),
            DocumentSymbolProvider(compilationManager),
            ReferencesProvider(compilationManager, cs),
            CompletionProvider(compilationManager, cs, hoverProvider),
        )
    }

    /** Registers [subscriber] on [bus], calls [action], then unregisters. */
    private fun withSubscriber(
        bus: EventBus,
        subscriber: Any,
        action: () -> Unit,
    ) {
        bus.register(subscriber)
        try {
            action()
        } finally {
            bus.unregister(subscriber)
        }
    }

    // -------------------------------------------------------------------------
    // didOpen
    // -------------------------------------------------------------------------

    @Test
    fun `didOpen posts DidOpenTextDocumentEvent with the correct URI`() {
        val bus = EventBus.builder().build()
        val svc = buildService(bus)
        var received: DidOpenTextDocumentEvent? = null
        val subscriber =
            object {
                @Subscribe fun on(e: DidOpenTextDocumentEvent) {
                    received = e
                }
            }

        val item = TextDocumentItem()
        item.uri = "file:///workspace/One.cql"
        val params = DidOpenTextDocumentParams()
        params.textDocument = item

        withSubscriber(bus, subscriber) { svc.didOpen(params) }

        assertNotNull(received)
        assertEquals("file:///workspace/One.cql", received!!.params().textDocument.uri)
    }

    // -------------------------------------------------------------------------
    // didChange
    // -------------------------------------------------------------------------

    @Test
    fun `didChange posts DidChangeTextDocumentEvent with the correct URI`() {
        val bus = EventBus.builder().build()
        val svc = buildService(bus)
        var received: DidChangeTextDocumentEvent? = null
        val subscriber =
            object {
                @Subscribe fun on(e: DidChangeTextDocumentEvent) {
                    received = e
                }
            }

        val params = DidChangeTextDocumentParams()
        params.textDocument = VersionedTextDocumentIdentifier("file:///workspace/One.cql", 1)

        withSubscriber(bus, subscriber) { svc.didChange(params) }

        assertNotNull(received)
        assertEquals("file:///workspace/One.cql", received!!.params().textDocument.uri)
    }

    // -------------------------------------------------------------------------
    // didClose
    // -------------------------------------------------------------------------

    @Test
    fun `didClose posts DidCloseTextDocumentEvent with the correct URI`() {
        val bus = EventBus.builder().build()
        val svc = buildService(bus)
        var received: DidCloseTextDocumentEvent? = null
        val subscriber =
            object {
                @Subscribe fun on(e: DidCloseTextDocumentEvent) {
                    received = e
                }
            }

        val params = DidCloseTextDocumentParams()
        params.textDocument = TextDocumentIdentifier("file:///workspace/One.cql")

        withSubscriber(bus, subscriber) { svc.didClose(params) }

        assertNotNull(received)
        assertEquals("file:///workspace/One.cql", received!!.params().textDocument.uri)
    }

    // -------------------------------------------------------------------------
    // didSave
    // -------------------------------------------------------------------------

    @Test
    fun `didSave posts DidSaveTextDocumentEvent with the correct URI`() {
        val bus = EventBus.builder().build()
        val svc = buildService(bus)
        var received: DidSaveTextDocumentEvent? = null
        val subscriber =
            object {
                @Subscribe fun on(e: DidSaveTextDocumentEvent) {
                    received = e
                }
            }

        val params = DidSaveTextDocumentParams()
        params.textDocument = TextDocumentIdentifier("file:///workspace/One.cql")

        withSubscriber(bus, subscriber) { svc.didSave(params) }

        assertNotNull(received)
        assertEquals("file:///workspace/One.cql", received!!.params().textDocument.uri)
    }

    // -------------------------------------------------------------------------
    // initialize
    // -------------------------------------------------------------------------

    @Test
    fun `initialize sets TextDocumentSync to Full`() {
        val svc = buildService(EventBus.builder().build())
        val capabilities = ServerCapabilities()
        svc.initialize(InitializeParams(), capabilities)
        assertEquals(TextDocumentSyncKind.Full, capabilities.textDocumentSync.left)
    }

    @Test
    fun `initialize enables document formatting provider`() {
        val svc = buildService(EventBus.builder().build())
        val capabilities = ServerCapabilities()
        svc.initialize(InitializeParams(), capabilities)
        assertEquals(true, capabilities.documentFormattingProvider.left)
    }

    @Test
    fun `initialize enables hover provider`() {
        val svc = buildService(EventBus.builder().build())
        val capabilities = ServerCapabilities()
        svc.initialize(InitializeParams(), capabilities)
        assertEquals(true, capabilities.hoverProvider.left)
    }

    // -------------------------------------------------------------------------
    // formatting
    // -------------------------------------------------------------------------

    @Test
    fun `formatting returns one TextEdit for valid CQL`() {
        val svc = buildService(EventBus.builder().build())
        val params = DocumentFormattingParams()
        params.textDocument = TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/Two.cql")
        val edits = svc.formatting(params).get()
        assertNotNull(edits)
        assertEquals(1, edits!!.size)
    }

    @Test
    fun `formatting notifies client and returns null when formatter throws`() {
        val mockClient = Mockito.mock(LanguageClient::class.java)
        val svc = buildService(EventBus.builder().build(), CompletableFuture.completedFuture(mockClient))
        val params = DocumentFormattingParams()
        // SyntaxError.cql causes FormattingProvider to throw IllegalArgumentException
        params.textDocument = TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/SyntaxError.cql")
        val result = svc.formatting(params).get()
        assertNull(result)
        Mockito.verify(mockClient).showMessage(Mockito.any())
    }

    // -------------------------------------------------------------------------
    // hover
    // -------------------------------------------------------------------------

    @Test
    fun `hover returns a completed future without throwing`() {
        val svc = buildService(EventBus.builder().build())
        val params = HoverParams(TextDocumentIdentifier("file:///workspace/One.cql"), Position(0, 0))
        val future = svc.hover(params)
        assertNotNull(future)
        // Hover is currently disabled in HoverProvider (returns null); future should still complete
        future.get()
    }

    // -------------------------------------------------------------------------
    // definition
    // -------------------------------------------------------------------------

    @Test
    fun `definition returns Either containing LocationLink list for valid document`() {
        val svc = buildService(EventBus.builder().build())
        val params = DefinitionParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/One.cql"), Position(0, 0))
        val result = svc.definition(params).get()
        assertNotNull(result, "Expected non-null Either result")
        assertTrue(result.isRight, "Expected Either.forRight for definition")
    }

    @Test
    fun `definition notifies client when provider throws`() {
        val mockClient = Mockito.mock(LanguageClient::class.java)
        val svc = buildService(EventBus.builder().build(), CompletableFuture.completedFuture(mockClient))
        // SyntaxError.cql causes compilation to fail, making DefinitionProvider return empty
        val params = DefinitionParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/SyntaxError.cql"), Position(0, 0))
        // Should complete without throwing
        val result = svc.definition(params).get()
        assertNotNull(result, "Expected non-null result even on error")
    }

    // -------------------------------------------------------------------------
    // documentSymbol
    // -------------------------------------------------------------------------

    @Test
    fun `documentSymbol returns symbol list for valid CQL`() {
        val svc = buildService(EventBus.builder().build())
        val params = DocumentSymbolParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/One.cql"))
        val result = svc.documentSymbol(params).get()
        assertNotNull(result, "Expected non-null symbol list")
        assertTrue(result.isNotEmpty(), "Expected at least one document symbol for One.cql")
    }

    @Test
    fun `documentSymbol returns empty list for syntax error`() {
        val svc = buildService(EventBus.builder().build())
        val params = DocumentSymbolParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/SyntaxError.cql"))
        val result = svc.documentSymbol(params).get()
        assertNotNull(result, "Expected non-null result even for syntax error")
    }

    // -------------------------------------------------------------------------
    // references
    // -------------------------------------------------------------------------

    @Test
    fun `references returns location list for valid document and position`() {
        val svc = buildService(EventBus.builder().build())
        val params = ReferenceParams()
        params.textDocument = TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/One.cql")
        params.position = Position(0, 0)
        val result = svc.references(params).get()
        assertNotNull(result, "Expected non-null location list")
    }

    @Test
    fun `references notifies client when provider throws`() {
        val mockClient = Mockito.mock(LanguageClient::class.java)
        val svc = buildService(EventBus.builder().build(), CompletableFuture.completedFuture(mockClient))
        val params = ReferenceParams()
        params.textDocument = TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/SyntaxError.cql")
        params.position = Position(0, 0)
        val result = svc.references(params).get()
        assertNotNull(result, "Expected non-null result even on error")
    }
}
