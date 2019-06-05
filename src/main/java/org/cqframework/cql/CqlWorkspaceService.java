package org.cqframework.cql;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class CqlWorkspaceService implements WorkspaceService {
    private static final Logger LOG = Logger.getLogger("main");

    private final CompletableFuture<LanguageClient> client;
    private final CqlLanguageServer server;

    private boolean clientSupportsWorkspaceFolders = false;

    private Map<String,WorkspaceFolder> workspaceFolders = new HashMap<String, WorkspaceFolder>();
    
    
    CqlWorkspaceService(CompletableFuture<LanguageClient> client, CqlLanguageServer server) {
        this.client = client;
        this.server = server;
        this.clientSupportsWorkspaceFolders = false;
    }

    public void initialize(List<WorkspaceFolder> folders, Boolean clientSupportsWorkspaceFolders) {
        this.addFolders(folders);

        if (clientSupportsWorkspaceFolders != null && clientSupportsWorkspaceFolders.booleanValue()) {
            this.clientSupportsWorkspaceFolders = true;
        }
    }

    @Override
	public void didChangeWorkspaceFolders(DidChangeWorkspaceFoldersParams params) {
        this.addFolders(params.getEvent().getAdded());
        this.removeFolders(params.getEvent().getRemoved());
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
    }

    public Collection<WorkspaceFolder> getWorkspaceFolders() {
        if (this.clientSupportsWorkspaceFolders) {
            List<WorkspaceFolder> folders = this.client.join().workspaceFolders().join();
            if (folders != null) {
                return folders;
            }
        }
  
        return this.workspaceFolders.values();
    }

    private void addFolders(List<WorkspaceFolder> folders) {
        for (WorkspaceFolder f : folders) {
            workspaceFolders.putIfAbsent(f.getUri(), f);
        }
    }

    private void removeFolders(List<WorkspaceFolder> folders) {
        for (WorkspaceFolder f : folders) {
            if (workspaceFolders.containsKey(f.getUri())) {
                workspaceFolders.remove(f.getUri());
            }
        }
    }
}
