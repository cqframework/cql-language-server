package org.opencds.cqf.cql.ls.server.service

import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import org.greenrobot.eventbus.EventBus
import org.opencds.cqf.cql.ls.server.event.DidChangeTextDocumentEvent
import org.opencds.cqf.cql.ls.server.event.DidCloseTextDocumentEvent
import org.opencds.cqf.cql.ls.server.event.DidOpenTextDocumentEvent
import org.opencds.cqf.cql.ls.server.event.DidSaveTextDocumentEvent
import org.opencds.cqf.cql.ls.server.provider.DefinitionProvider
import org.opencds.cqf.cql.ls.server.provider.DocumentSymbolProvider
import org.opencds.cqf.cql.ls.server.provider.FormattingProvider
import org.opencds.cqf.cql.ls.server.provider.HoverProvider
import org.opencds.cqf.cql.ls.server.provider.ReferencesProvider
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

class CqlTextDocumentService(
    private val client: CompletableFuture<LanguageClient>,
    private val hoverProvider: HoverProvider,
    private val formattingProvider: FormattingProvider,
    private val eventBus: EventBus,
    private val definitionProvider: DefinitionProvider,
    private val documentSymbolProvider: DocumentSymbolProvider,
    private val referencesProvider: ReferencesProvider,
) : TextDocumentService {
    companion object {
        private val log = LoggerFactory.getLogger(CqlTextDocumentService::class.java)
    }

    fun initialize(
        params: InitializeParams,
        serverCapabilities: ServerCapabilities,
    ) {
        serverCapabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
        serverCapabilities.setDocumentFormattingProvider(true)
        serverCapabilities.setHoverProvider(true)
        serverCapabilities.setDefinitionProvider(true)
        serverCapabilities.setDocumentSymbolProvider(true)
        serverCapabilities.setReferencesProvider(true)
    }

    // LSP4J declares CompletableFuture<Hover>, but hoverProvider returns Hover?.
    // The cast through Hover? is safe: at runtime both types erase to the same raw
    // CompletableFuture, and null is a valid LSP hover response.
    @Suppress("UNCHECKED_CAST")
    override fun hover(params: HoverParams): CompletableFuture<Hover> =
        CompletableFuture.supplyAsync<Hover?> { hoverProvider.hover(params) }
            .exceptionally { notifyClient(it) } as CompletableFuture<Hover>

    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> {
        return CompletableFuture.supplyAsync<List<TextEdit>> {
            formattingProvider.format(params.textDocument.uri)
        }.exceptionally { notifyClient(it) }
    }

    override fun definition(
        params: DefinitionParams,
    ): CompletableFuture<Either<List<Location>, List<org.eclipse.lsp4j.LocationLink>>> {
        val result =
            CompletableFuture.supplyAsync<Either<List<Location>, List<org.eclipse.lsp4j.LocationLink>>> {
                Either.forRight(definitionProvider.definition(params))
            }.exceptionally { notifyClient(it) }
        @Suppress("UNCHECKED_CAST")
        return result as CompletableFuture<Either<List<Location>, List<org.eclipse.lsp4j.LocationLink>>>
    }

    override fun documentSymbol(
        params: DocumentSymbolParams,
    ): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
        val result =
            CompletableFuture.supplyAsync<List<Either<SymbolInformation, DocumentSymbol>>> {
                documentSymbolProvider.documentSymbol(params).map {
                    Either.forRight<SymbolInformation, DocumentSymbol>(it)
                }
            }.exceptionally { notifyClient(it) }
        @Suppress("UNCHECKED_CAST")
        return result as CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>>
    }

    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> {
        val result =
            CompletableFuture.supplyAsync<List<Location>> {
                referencesProvider.references(params)
            }.exceptionally { notifyClient(it) }
        @Suppress("UNCHECKED_CAST")
        return result as CompletableFuture<List<Location>>
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
