package org.opencds.cqf.cql.ls.server.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.greenrobot.eventbus.EventBus;
import org.opencds.cqf.cql.ls.server.event.DidChangeTextDocumentEvent;
import org.opencds.cqf.cql.ls.server.event.DidCloseTextDocumentEvent;
import org.opencds.cqf.cql.ls.server.event.DidOpenTextDocumentEvent;
import org.opencds.cqf.cql.ls.server.event.DidSaveTextDocumentEvent;
import org.opencds.cqf.cql.ls.server.provider.FormattingProvider;
import org.opencds.cqf.cql.ls.server.provider.HoverProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CqlTextDocumentService implements TextDocumentService {
    private static final Logger log = LoggerFactory.getLogger(CqlTextDocumentService.class);


    private final CompletableFuture<LanguageClient> client;
    private final FormattingProvider formattingProvider;
    private final HoverProvider hoverProvider;

    public CqlTextDocumentService(CompletableFuture<LanguageClient> client,
            HoverProvider hoverProvider, FormattingProvider formattingProvider) {
        this.client = client;
        this.formattingProvider = formattingProvider;
        this.hoverProvider = hoverProvider;
    }

    @SuppressWarnings("java:S125") // Keeping the commented code for future reference
    public void initialize(InitializeParams params, ServerCapabilities serverCapabilities) {
        serverCapabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
        // c.setDefinitionProvider(true);
        // c.setCompletionProvider(new CompletionOptions(true, ImmutableList.of(".")));
        serverCapabilities.setDocumentFormattingProvider(true);
        // serverCapabilities.setDocumentRangeFormattingProvider(false);
        serverCapabilities.setHoverProvider(true);
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
        EventBus.getDefault().post(new DidOpenTextDocumentEvent(params));
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        EventBus.getDefault().post(new DidChangeTextDocumentEvent(params));
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        EventBus.getDefault().post(new DidCloseTextDocumentEvent(params));
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        EventBus.getDefault().post(new DidSaveTextDocumentEvent(params));
    }
}
