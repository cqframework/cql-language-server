package org.opencds.cqf.cql.ls.server;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.services.LanguageClient;
import org.opencds.cqf.cql.ls.core.ContentService;

public class ServerContext {

    private final CompletableFuture<LanguageClient> client;
    private final ActiveContent activeContent;
    private final List<WorkspaceFolder> workspaceFolders;
    private final ContentService contentService;

    public ServerContext(CompletableFuture<LanguageClient> client, ActiveContent activeContent, List<WorkspaceFolder> workspaceFolders) {
        this(client, activeContent, workspaceFolders, new FileContentService(workspaceFolders));
    }

    public ServerContext(CompletableFuture<LanguageClient> client, ActiveContent activeContent, List<WorkspaceFolder> workspaceFolders, ContentService contentService) {
        this.client = client;
        this.workspaceFolders = workspaceFolders;
        this.activeContent = activeContent;
        this.contentService = contentService;
    }

    CompletableFuture<LanguageClient> client() {
        return this.client;
    }

    List<WorkspaceFolder> workspaceFolders() {
        return this.workspaceFolders;
    }

    ActiveContent activeContent() {
        return this.activeContent;
    }

    ContentService contentService() {
        return this.contentService;
    }
}
