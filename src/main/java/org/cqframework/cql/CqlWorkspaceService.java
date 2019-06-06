package org.cqframework.cql;

import org.cqframework.cql.cql2elm.CqlTranslator;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.WorkspaceService;

import com.google.gson.JsonElement;

import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {

        try 
        {
            String command = params.getCommand();
            // There's currently not a "show text file" or similar command in the LSP spec,
            // So it's not client agnostic. The client has to know that the result of this command
            // is XML and display it accordingly.
            if (command.equals("Other.ViewXML")) {
                String uri = ((JsonElement)params.getArguments().get(0)).getAsString();
                Optional<String> content = ((CqlTextDocumentService)this.server.getTextDocumentService()).activeContent(new URI(uri));
                if (content.isPresent()) {
                    CqlTranslator translator = this.server.getTranslationManager().translate(content.get());
                    return CompletableFuture.completedFuture(translator.toXml());
                }
            }
            else {
                this.client.join().showMessage(new MessageParams(MessageType.Error, String.format("Unknown Command %s", command)));
            }
        }
        catch (Exception e) {
            return null;
        }

        return null;
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
