package org.cqframework.cql;

import com.google.common.collect.ImmutableList;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import javax.tools.*;
import java.io.PrintWriter;
import java.io.StringWriter;
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

class CqlLanguageServer implements LanguageServer {
    private static final Logger LOG = Logger.getLogger("main");

    private final CompletableFuture<LanguageClient> client = new CompletableFuture<>();
    private final CqlTextDocumentService textDocuments = new CqlTextDocumentService(client, this);
    private final CqlWorkspaceService workspace = new CqlWorkspaceService(client, this, textDocuments);

    private CqlTranslationManager translationManager;
    CqlTranslationManager getTranslationManager() {
        return translationManager;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        translationManager = new CqlTranslationManager(textDocuments.getLibrarySourceProvider());

        InitializeResult result = new InitializeResult();
        ServerCapabilities c = new ServerCapabilities();

        c.setTextDocumentSync(TextDocumentSyncKind.Full);
        //c.setDefinitionProvider(true);
        //c.setCompletionProvider(new CompletionOptions(true, ImmutableList.of(".")));
        c.setDocumentFormattingProvider(true);
        c.setDocumentRangeFormattingProvider(false);
        //c.setHoverProvider(true);
        //c.setWorkspaceSymbolProvider(true);
        //c.setReferencesProvider(true);
        //c.setDocumentSymbolProvider(true);
        //c.setCodeActionProvider(true);
        //c.setExecuteCommandProvider(
        //        new ExecuteCommandOptions(ImmutableList.of("Java.importClass")));
        //c.setSignatureHelpProvider(new SignatureHelpOptions(ImmutableList.of("(", ",")));

        result.setCapabilities(c);

        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {}

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocuments;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspace;
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

    static void onDiagnostic(javax.tools.Diagnostic<? extends JavaFileObject> diagnostic) {
        Level level = level(diagnostic.getKind());
        String message = diagnostic.getMessage(null);

        LOG.log(level, message);
    }

    private static Level level(javax.tools.Diagnostic.Kind kind) {
        switch (kind) {
            case ERROR:
                return Level.SEVERE;
            case WARNING:
            case MANDATORY_WARNING:
                return Level.WARNING;
            case NOTE:
                return Level.INFO;
            case OTHER:
            default:
                return Level.FINE;
        }
    }
}
