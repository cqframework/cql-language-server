package org.opencds.cqf.cql.ls.server.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.greenrobot.eventbus.EventBus;
import org.opencds.cqf.cql.ls.server.event.DidChangeTextDocumentEvent;
import org.opencds.cqf.cql.ls.server.event.DidCloseTextDocumentEvent;
import org.opencds.cqf.cql.ls.server.event.DidOpenTextDocumentEvent;
import org.opencds.cqf.cql.ls.server.event.DidSaveTextDocumentEvent;
import org.opencds.cqf.cql.ls.server.provider.GoToDefinitionProvider;
import org.opencds.cqf.cql.ls.server.provider.FormattingProvider;
import org.opencds.cqf.cql.ls.server.provider.HoverProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CqlTextDocumentService implements TextDocumentService {
    private static final Logger log = LoggerFactory.getLogger(CqlTextDocumentService.class);


    private final CompletableFuture<LanguageClient> client;
    private final FormattingProvider formattingProvider;
    private final HoverProvider hoverProvider;
    private final GoToDefinitionProvider goToDefinitionProvider;
    private final EventBus eventBus;

    public CqlTextDocumentService(CompletableFuture<LanguageClient> client,
                                  HoverProvider hoverProvider, FormattingProvider formattingProvider, GoToDefinitionProvider goToDefinitionProvider, EventBus eventBus) {
        this.client = client;
        this.formattingProvider = formattingProvider;
        this.hoverProvider = hoverProvider;
        this.goToDefinitionProvider = goToDefinitionProvider;
        this.eventBus = eventBus;
    }

    @SuppressWarnings("java:S125") // Keeping the commented code for future reference
    public void initialize(InitializeParams params, ServerCapabilities serverCapabilities) {
        serverCapabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
        // c.setDefinitionProvider(true);
        // c.setCompletionProvider(new CompletionOptions(true, ImmutableList.of(".")));
        serverCapabilities.setDocumentFormattingProvider(true);
        // serverCapabilities.setDocumentRangeFormattingProvider(false);
        serverCapabilities.setHoverProvider(true);
        serverCapabilities.setDefinitionProvider(true);
//        serverCapabilities.set
        // c.setReferencesProvider(true);
        // c.setDocumentSymbolProvider(true);
        // c.setCodeActionProvider(true);
        // c.setSignatureHelpProvider(new SignatureHelpOptions(ImmutableList.of("(",
        // ",")));
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams position) {
        return CompletableFuture.supplyAsync(() -> this.hoverProvider.hover(position))
                .exceptionally(this::notifyClient);
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        return CompletableFuture.supplyAsync(() -> this.goToDefinitionProvider.getDefinitionLocation(params))
                .exceptionally(this::notifyClient);
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        return CompletableFuture
                .<List<? extends TextEdit>>supplyAsync(
                        () -> this.formattingProvider.format(params.getTextDocument().getUri()))
                .exceptionally(this::notifyClient);
    }


    private <T> T notifyClient(Throwable e) {
        log.error("error", e);
        this.client.join().showMessage(new MessageParams(MessageType.Error, e.getMessage()));
        return null;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        eventBus.post(new DidOpenTextDocumentEvent(params));
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        eventBus.post(new DidChangeTextDocumentEvent(params));
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        eventBus.post(new DidCloseTextDocumentEvent(params));
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        eventBus.post(new DidSaveTextDocumentEvent(params));
    }
}
