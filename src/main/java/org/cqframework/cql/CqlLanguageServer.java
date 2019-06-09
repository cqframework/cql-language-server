package org.cqframework.cql;

import com.google.common.collect.ImmutableList;

import org.cqframework.cql.manager.CqlTranslationManager;
import org.cqframework.cql.service.CqlTextDocumentService;
import org.cqframework.cql.service.CqlWorkspaceService;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import javax.tools.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/*
TODO: Formatting
TODO: Error messsages
TODO: Completion for types in a retrieve
TODO: Completion for properties
TODO: Completion for operators
TODO: Completion for functions
TODO: Completion for function arguments
TODO: Completion for expressions, parameters, code systems, value sets, codes, and concepts
 */

public class CqlLanguageServer implements LanguageServer {
    private static final Logger LOG = Logger.getLogger("main");

    private final CqlWorkspaceService workspaceService;
    private final CqlTextDocumentService textDocumentService;
    private final CompletableFuture<LanguageClient> client = new CompletableFuture<>();

    private CqlTranslationManager translationManager;

    public CqlLanguageServer() {
        this.textDocumentService = new CqlTextDocumentService(client, this);
        this.workspaceService = new CqlWorkspaceService(client, this);
        this.translationManager = new CqlTranslationManager(this.textDocumentService, this.workspaceService);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) { 
        try {  
            this.initializeWorkspaceService(params);
            this.initializeTextDocumentService(params);
            
            InitializeResult result = new InitializeResult();
            result.setCapabilities(this.getServerCapabilities());

            return CompletableFuture.completedFuture(result);
        }
        catch (Exception e) {
            LOG.log(Level.SEVERE, "failed to initialize with error: " + e.getMessage());
            return null;
        }
    }

    private void initializeTextDocumentService(InitializeParams params) {
        // Nothing to do for the moment.
    }

    private void initializeWorkspaceService(InitializeParams params) {
        List<WorkspaceFolder> workspaceFolders = new ArrayList<WorkspaceFolder>();
        if (params.getWorkspaceFolders() != null)
        {
            workspaceFolders.addAll(params.getWorkspaceFolders());
        }

        if (params.getRootUri() != null) {
            workspaceFolders.add(new WorkspaceFolder(params.getRootUri()));
        }

        this.workspaceService.initialize(workspaceFolders);
    }

    private ServerCapabilities getServerCapabilities() {
        ServerCapabilities c = new ServerCapabilities();
        // Register for workspace change notifications
        WorkspaceFoldersOptions wfo = new WorkspaceFoldersOptions();
        wfo.setChangeNotifications(true);
        WorkspaceServerCapabilities wsc = new WorkspaceServerCapabilities();
        wsc.setWorkspaceFolders(wfo);
        c.setWorkspace(wsc);

        c.setTextDocumentSync(TextDocumentSyncKind.Full);
        //c.setDefinitionProvider(true);
        //c.setCompletionProvider(new CompletionOptions(true, ImmutableList.of(".")));
        c.setDocumentFormattingProvider(true);
        c.setDocumentRangeFormattingProvider(false);
        //c.setHoverProvider(true);
        //c.setWorkspaceSymbolProvider(true);
        //c.setReferencesProvider(true);
        //c.setDocumentSymbolProvider(true);
        // c.setCodeActionProvider(true);
        c.setExecuteCommandProvider(
               new ExecuteCommandOptions(ImmutableList.of("Other.ViewXML")));
        //c.setSignatureHelpProvider(new SignatureHelpOptions(ImmutableList.of("(", ",")));

        return c;
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {}

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    public CqlTranslationManager getTranslationManager() {
        return translationManager;
    }

    void installClient(LanguageClient client) {
        this.client.complete(client);

        Handler sendToClient =
                new Handler() {
                    @Override
                    public void publish(LogRecord record) {
                        String message = record.getMessage();

                        if (record.getThrown() != null) {
                            StringWriter trace = new StringWriter();

                            record.getThrown().printStackTrace(new PrintWriter(trace));
                            message += "\n" + trace;
                        }

                        client.logMessage(
                                new MessageParams(
                                        messageType(record.getLevel().intValue()), message));
                    }

                    private MessageType messageType(int level) {
                        if (level >= Level.SEVERE.intValue()) return MessageType.Error;
                        else if (level >= Level.WARNING.intValue()) return MessageType.Warning;
                        else if (level >= Level.INFO.intValue()) return MessageType.Info;
                        else return MessageType.Log;
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() throws SecurityException {}
                };

        Logger.getLogger("").addHandler(sendToClient);
    }
}
