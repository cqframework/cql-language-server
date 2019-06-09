package org.cqframework.cql.service;

import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import com.google.gson.JsonElement;

import org.cqframework.cql.CqlLanguageServer;
import org.cqframework.cql.cql2elm.CqlTranslator;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.WorkspaceService;

public class CqlWorkspaceService implements WorkspaceService {
    private static final Logger LOG = Logger.getLogger("main");

    private final CompletableFuture<LanguageClient> client;
    private final CqlLanguageServer server;

    private Map<String,WorkspaceFolder> workspaceFolders = new HashMap<String, WorkspaceFolder>();
    
    
    public CqlWorkspaceService(CompletableFuture<LanguageClient> client, CqlLanguageServer server) {
        this.client = client;
        this.server = server;
    }

    public void initialize(List<WorkspaceFolder> folders) {
        this.addFolders(folders);
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
                String uriString = ((JsonElement)params.getArguments().get(0)).getAsString();
                URI uri = new URI(uriString);
                Optional<String> content = ((CqlTextDocumentService)this.server.getTextDocumentService()).activeContent(uri);
                if (content.isPresent()) {
                    CqlTranslator translator = this.server.getTranslationManager().translate(uri, content.get());
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
