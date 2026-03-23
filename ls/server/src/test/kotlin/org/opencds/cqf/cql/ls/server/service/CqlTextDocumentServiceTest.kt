package org.opencds.cqf.cql.ls.server.service

import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.services.LanguageClient
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.server.event.DidChangeTextDocumentEvent
import org.opencds.cqf.cql.ls.server.event.DidCloseTextDocumentEvent
import org.opencds.cqf.cql.ls.server.event.DidOpenTextDocumentEvent
import org.opencds.cqf.cql.ls.server.event.DidSaveTextDocumentEvent
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.provider.FormattingProvider
import org.opencds.cqf.cql.ls.server.provider.HoverProvider
import java.util.concurrent.CompletableFuture

class CqlTextDocumentServiceTest {
    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildService(bus: EventBus): CqlTextDocumentService {
        val cs = TestContentService()
        val compilationManager = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs))
        return CqlTextDocumentService(
            CompletableFuture<LanguageClient>(),
            HoverProvider(compilationManager),
            FormattingProvider(cs),
            bus,
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
}
