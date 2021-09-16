package org.opencds.cqf.cql.ls;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceFoldersOptions;
import org.eclipse.lsp4j.WorkspaceServerCapabilities;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.opencds.cqf.cql.ls.manager.CqlTranslationManager;
import org.opencds.cqf.cql.ls.plugin.CommandContribution;
import org.opencds.cqf.cql.ls.plugin.CqlLanguageServerPlugin;
import org.opencds.cqf.cql.ls.plugin.CqlLanguageServerPluginFactory;
import org.opencds.cqf.cql.ls.service.CqlTextDocumentService;
import org.opencds.cqf.cql.ls.service.CqlWorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class CqlLanguageServer implements LanguageServer, LanguageClientAware {
    private static final Logger Log = LoggerFactory.getLogger(CqlLanguageServer.class);

    private final CqlWorkspaceService workspaceService;
    private final CqlTextDocumentService textDocumentService;
    private final CompletableFuture<LanguageClient> client = new CompletableFuture<>();

    private CqlTranslationManager translationManager;

    private List<CqlLanguageServerPlugin> plugins;
    private CompletableFuture<List<CommandContribution>> commandContributions = new CompletableFuture<>();

    private CompletableFuture<Void> exited;

    private ActiveContent activeContent;

    public CqlLanguageServer() {
        this.exited = new CompletableFuture<>();
        this.activeContent = new ActiveContent();
        this.translationManager = new CqlTranslationManager(activeContent);
        this.textDocumentService = new CqlTextDocumentService(client, this.activeContent, this.translationManager);
        this.workspaceService = new CqlWorkspaceService(client, this.commandContributions);
        this.plugins = new ArrayList<>();
        this.loadPlugins();
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        try {
            this.initializeWorkspaceService(params);
            this.initializeTextDocumentService(params);

            InitializeResult result = new InitializeResult();
            result.setCapabilities(this.getServerCapabilities());

            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            Log.error("failed to initialize with error: {}", e.getMessage());
            return FuturesHelper.failedFuture(e);
        }
    }

    private void initializeTextDocumentService(InitializeParams params) {
        // Nothing to do for the moment.
    }

    // getRootUri is deprecated, will keep for a few revision until the client
    // is updated.
    @SuppressWarnings("deprecation")
    private void initializeWorkspaceService(InitializeParams params) {
        List<WorkspaceFolder> workspaceFolders = new ArrayList<WorkspaceFolder>();
        if (params.getWorkspaceFolders() != null) {
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
        // c.setDefinitionProvider(true);
        // c.setCompletionProvider(new CompletionOptions(true, ImmutableList.of(".")));
        c.setDocumentFormattingProvider(true);
        c.setDocumentRangeFormattingProvider(false);
        c.setHoverProvider(true);
        // c.setWorkspaceSymbolProvider(true);
        // c.setReferencesProvider(true);
        // c.setDocumentSymbolProvider(true);
        // c.setCodeActionProvider(true);
        c.setExecuteCommandProvider(new ExecuteCommandOptions(this.workspaceService.getSupportedCommands()));
        // c.setSignatureHelpProvider(new SignatureHelpOptions(ImmutableList.of("(",
        // ",")));

        return c;
    }

    protected void loadPlugins() {
        ServiceLoader<CqlLanguageServerPluginFactory> pluginFactories = ServiceLoader
                .load(CqlLanguageServerPluginFactory.class);

        List<CommandContribution> commandContributions = new ArrayList<>();

        for (CqlLanguageServerPluginFactory pluginFactory : pluginFactories) {
            CqlLanguageServerPlugin plugin = pluginFactory.createPlugin(this.client, this.workspaceService, this.textDocumentService, this.translationManager);
            this.plugins.add(plugin);
            Log.debug("Loading plugin {}", plugin.getName());
            if (plugin.getCommandContribution() != null) {
                commandContributions.add(plugin.getCommandContribution());
            }
        }

        commandContributions.add(this.textDocumentService.getCommandContribution());

        this.commandContributions.complete(commandContributions);
    }

    @Override
    public CompletableFuture<Object> shutdown() {
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

    public CqlTranslationManager getTranslationManager() {
        return translationManager;
    }

    @Override
    public void connect(LanguageClient client) {
        this.client.complete(client);

        // Handler sendToClient =
        // new Handler() {
        // @Override
        // public void publish(LogRecord record) {
        // if (record == null) {
        // return;
        // }

        // String message = record.getMessage();

        // if (message == null) {
        // return;
        // }

        // // TODO: filter out HAPI messages
        // if (record.getLevel().intValue() <= Level.INFO.intValue()) {
        // return;
        // }

        // if (record.getThrown() != null) {
        // StringWriter trace = new StringWriter();

        // record.getThrown().printStackTrace(new PrintWriter(trace));
        // message += "\n" + trace;
        // }

        // client.logMessage(
        // new MessageParams(
        // messageType(record.getLevel().intValue()), message));
        // }

        // private MessageType messageType(int level) {
        // if (level >= Level.SEVERE.intValue()) return MessageType.Error;
        // else if (level >= Level.WARNING.intValue()) return MessageType.Warning;
        // else if (level >= Level.INFO.intValue()) return MessageType.Info;
        // else return MessageType.Log;
        // }

        // @Override
        // public void flush() {}

        // @Override
        // public void close() throws SecurityException {}
        // };

        // java.util.logging.Logger.getLogger("").addHandler(sendToClient);
    }
}
