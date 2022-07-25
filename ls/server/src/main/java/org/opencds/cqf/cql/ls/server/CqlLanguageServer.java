package org.opencds.cqf.cql.ls.server;

import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.opencds.cqf.cql.ls.server.service.CqlTextDocumentService;
import org.opencds.cqf.cql.ls.server.service.CqlWorkspaceService;
import org.opencds.cqf.cql.ls.server.utility.Futures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class CqlLanguageServer implements LanguageServer, LanguageClientAware {
    private static final Logger log = LoggerFactory.getLogger(CqlLanguageServer.class);

    private final CqlWorkspaceService workspaceService;
    private final CqlTextDocumentService textDocumentService;
    private final CompletableFuture<LanguageClient> client;

    private final CompletableFuture<Void> exited;

    public CqlLanguageServer(CompletableFuture<LanguageClient> client,
            CqlWorkspaceService workspaceService, CqlTextDocumentService textDocumentService) {
        this.exited = new CompletableFuture<>();
        this.client = client;
        this.workspaceService = workspaceService;
        this.textDocumentService = textDocumentService;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        try {
            ServerCapabilities serverCapabilities = new ServerCapabilities();
            this.workspaceService.initialize(params, serverCapabilities);
            this.textDocumentService.initialize(params, serverCapabilities);

            InitializeResult result = new InitializeResult();
            result.setCapabilities(serverCapabilities);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            log.error("failed to initialize with error: {}", e.getMessage());
            return Futures.failed(e);
        }
    }

    @Override
    public void initialized(InitializedParams params) {
        // Nothing to do, currently.
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        // Nothing to do currently
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        this.exited.complete(null);
    }

    public CompletableFuture<Void> exited() {
        return this.exited;
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    @Override
    public void connect(LanguageClient client) {
        this.client.complete(client);
    }
}
