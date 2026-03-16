package org.opencds.cqf.cql.ls.server.service

import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import org.greenrobot.eventbus.EventBus
import org.opencds.cqf.cql.ls.server.event.DidChangeTextDocumentEvent
import org.opencds.cqf.cql.ls.server.event.DidCloseTextDocumentEvent
import org.opencds.cqf.cql.ls.server.event.DidOpenTextDocumentEvent
import org.opencds.cqf.cql.ls.server.event.DidSaveTextDocumentEvent
import org.opencds.cqf.cql.ls.server.provider.FormattingProvider
import org.opencds.cqf.cql.ls.server.provider.HoverProvider
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

class CqlTextDocumentService(
    private val client: CompletableFuture<LanguageClient>,
    private val hoverProvider: HoverProvider,
    private val formattingProvider: FormattingProvider,
    private val eventBus: EventBus
) : TextDocumentService {

    companion object {
        private val log = LoggerFactory.getLogger(CqlTextDocumentService::class.java)
    }

    @Suppress("java:S125") // Keeping the commented code for future reference
    fun initialize(params: InitializeParams, serverCapabilities: ServerCapabilities) {
        serverCapabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
        // c.setDefinitionProvider(true);
        // c.setCompletionProvider(new CompletionOptions(true, ImmutableList.of(".")));
        serverCapabilities.setDocumentFormattingProvider(true)
        // serverCapabilities.setDocumentRangeFormattingProvider(false);
        serverCapabilities.setHoverProvider(true)
        // c.setReferencesProvider(true);
        // c.setDocumentSymbolProvider(true);
        // c.setCodeActionProvider(true);
        // c.setSignatureHelpProvider(new SignatureHelpOptions(ImmutableList.of("(", ",")));
    }

    @Suppress("UNCHECKED_CAST")
    override fun hover(position: HoverParams): CompletableFuture<Hover> {
        return CompletableFuture.supplyAsync { hoverProvider.hover(position) }
            .exceptionally { notifyClient(it) } as CompletableFuture<Hover>
    }

    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<out TextEdit>> {
        return CompletableFuture.supplyAsync<List<out TextEdit>> {
            formattingProvider.format(params.textDocument.uri)
        }.exceptionally { notifyClient(it) }
    }

    private fun <T> notifyClient(e: Throwable): T? {
        log.error("error", e)
        client.join().showMessage(MessageParams(MessageType.Error, e.message))
        return null
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        eventBus.post(DidOpenTextDocumentEvent(params))
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        eventBus.post(DidChangeTextDocumentEvent(params))
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        eventBus.post(DidCloseTextDocumentEvent(params))
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        eventBus.post(DidSaveTextDocumentEvent(params))
    }
}
