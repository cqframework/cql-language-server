package org.opencds.cqf.cql.ls.server;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.services.LanguageClient;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.server.service.FileContentService;

public class ServerContext {

    private final CompletableFuture<LanguageClient> client;
    private final List<WorkspaceFolder> workspaceFolders;
    private final ContentService contentService;

    public ServerContext(CompletableFuture<LanguageClient> client,
            List<WorkspaceFolder> workspaceFolders) {
        this(client, workspaceFolders, new FileContentService(workspaceFolders));
    }

    public ServerContext(CompletableFuture<LanguageClient> client,
            List<WorkspaceFolder> workspaceFolders, ContentService contentService) {
        this.client = client;
        this.workspaceFolders = workspaceFolders;
        this.contentService = contentService;
    }

    public ContentService contentService() {
        return this.contentService;
    }

    public CompletableFuture<LanguageClient> client() {
        return this.client;
    }

    public List<WorkspaceFolder> workspaceFolders() {
        return this.workspaceFolders;
    }
}
