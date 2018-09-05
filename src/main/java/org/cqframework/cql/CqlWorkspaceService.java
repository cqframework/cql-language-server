package org.cqframework.cql;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

class CqlWorkspaceService implements WorkspaceService {
    private final CompletableFuture<LanguageClient> client;
    private final CqlLanguageServer server;
    private final CqlTextDocumentService textDocuments;

    CqlWorkspaceService(
            CompletableFuture<LanguageClient> client,
            CqlLanguageServer server,
            CqlTextDocumentService textDocuments) {
        this.client = client;
        this.server = server;
        this.textDocuments = textDocuments;
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        LOG.info(params.toString());

        switch (params.getCommand()) {
            default:
                LOG.warning("Unknown command " + params.getCommand());
        }

        return CompletableFuture.completedFuture("Done");
    }

    @Override
    public CompletableFuture<List<? extends SymbolInformation>> symbol(WorkspaceSymbolParams params) {
        return null;
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams change) {
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        textDocuments.doLint(textDocuments.openFiles());
    }

    private static final Logger LOG = Logger.getLogger("main");
}
