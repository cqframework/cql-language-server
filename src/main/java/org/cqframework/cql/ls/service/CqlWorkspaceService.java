package org.cqframework.cql.ls.service;

import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
// import java.util.logging.Logger;

import com.google.gson.JsonElement;

import org.cqframework.cql.cql2elm.CqlTranslator;
import org.cqframework.cql.ls.CqlLanguageServer;
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
    // private static final Logger LOG = Logger.getLogger("main");

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
            String command = params.getCommand();
            switch (command) {
                case "Other.ViewXML":
                    return this.viewXml(params);
                case "Other.ExecuteCql":
                    // return this.executeCql(params);
                default:
                    this.client.join().showMessage(new MessageParams(MessageType.Error, String.format("Unknown Command %s", command)));
                    return null;
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


    // There's currently not a "show text file" or similar command in the LSP spec,
    // So it's not client agnostic. The client has to know that the result of this command
    // is XML and display it accordingly.
    private CompletableFuture<Object> viewXml(ExecuteCommandParams params) {
        try {
            String uriString = ((JsonElement)params.getArguments().get(0)).getAsString();
            URI uri = new URI(uriString);
            Optional<String> content = ((CqlTextDocumentService)this.server.getTextDocumentService()).activeContent(uri);
            if (content.isPresent()) {
                CqlTranslator translator = this.server.getTranslationManager().translate(uri, content.get());
                return CompletableFuture.completedFuture(translator.toXml());
            }

            return null;
        }
        catch(Exception e) {
            this.client.join().showMessage(new MessageParams(MessageType.Error, String.format("View XML failed with: %s", e.getMessage())));
            return null;
        }
    }

    // There's currently not a "show text file" or similar command in the LSP spec,
    // So it's not client agnostic. The client has to know that the result of this command
    // is text and display it accordingly.
    // private CompletableFuture<Object> executeCql(ExecuteCommandParams params) {
    //     try {
    //         String uriString = ((JsonElement)params.getArguments().get(0)).getAsString();
    //         URI uri = new URI(uriString);
    //         CqlTranslator translator = this.getTranslatorForUri(uri);

    //         Library library = CqlLibraryReader.read(new ByteArrayInputStream(translator.toXml().getBytes()));


    //         String path = null;
    //         String endpoint = null;
    //         String patientId = null;

    //         if(params.getArguments().size() >= 2) {
    //             path = ((JsonElement)params.getArguments().get(1)).getAsString();
    //         }

    //         if(params.getArguments().size() >= 3) {
    //             endpoint = ((JsonElement)params.getArguments().get(2)).getAsString();
    //         }

    //         if(params.getArguments().size() >= 4) {
    //             patientId = ((JsonElement)params.getArguments().get(3)).getAsString();
    //         }

    //         Context context = new Context(library);

    //         FileBasedFhirProvider dataProvider = new FileBasedFhirProvider(path, endpoint);

    //         context.registerDataProvider("http://hl7.org/fhir", dataProvider);


    //         StringBuilder builder = new StringBuilder();

    //         if (library.getStatements() != null) {
    //             for (org.cqframework.cql.elm.execution.ExpressionDef def : library.getStatements().getDef()) {
    //                 context.enterContext(def.getContext());
    //                 if (patientId != null && !patientId.isEmpty()) {
    //                     context.setContextValue(context.getCurrentContext(), patientId);
    //                 }
    //                 else {
    //                     context.setContextValue(context.getCurrentContext(), "null");
    //                 }
    
    //                 try {
    //                     builder.append(def.getName() + " : ");
    //                     Object res = def instanceof org.cqframework.cql.elm.execution.FunctionDef ? "Definition successfully validated" : def.getExpression().evaluate(context);
    
    //                     builder.append(res.toString() + "\n");
    //                 }
    //                 catch (RuntimeException re) {
    //                     re.printStackTrace();
    
    //                     String message = re.getMessage() != null ? re.getMessage() : re.getClass().getName();
    //                     builder.append(message + "\n");
    //                 }
    //             }
    //         }

    //         return CompletableFuture.completedFuture(builder.toString());
    //     }
    //     catch(Exception e) {
    //         this.client.join().showMessage(new MessageParams(MessageType.Error, String.format("Execute CQL failed with: %s", e.getMessage())));
    //         return null;
    //     }
    // }

    // private CqlTranslator getTranslatorForUri(URI uri) {
    //     Optional<String> content = ((CqlTextDocumentService)this.server.getTextDocumentService()).activeContent(uri);
    //     if (content.isPresent()) {
    //         return this.server.getTranslationManager().translate(uri, content.get());
    //     }
        
    //     return null;
    // }
}

