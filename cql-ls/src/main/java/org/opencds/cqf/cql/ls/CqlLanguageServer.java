package org.opencds.cqf.cql.ls;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.opencds.cqf.cql.ls.event.DidChangeWatchedFilesEvent;
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

    private final CqlTranslationManager translationManager;

    private final List<CqlLanguageServerPlugin> plugins;
    private final CompletableFuture<List<CommandContribution>> commandContributions = new CompletableFuture<>();

    private final CompletableFuture<Void> exited;

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

            ServerCapabilities serverCapabilities = new ServerCapabilities();
            this.initializeWorkspaceService(params, serverCapabilities);
            this.initializeTextDocumentService(params, serverCapabilities);

            InitializeResult result = new InitializeResult();
            result.setCapabilities(serverCapabilities);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            Log.error("failed to initialize with error: {}", e.getMessage());
            return FuturesHelper.failedFuture(e);
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND) 
    public void onMessageEvent(DidChangeWatchedFilesEvent event) {
        if (translationManager != null) {
            translationManager.clearCachedTranslatorOptions();
        }
    }

    @Override
    public void initialized(InitializedParams params) {
        this.textDocumentService.initialized();
        this.workspaceService.initialized();
    }

    private void initializeTextDocumentService(InitializeParams params, ServerCapabilities serverCapabilities) {
        this.textDocumentService.initialize(params, serverCapabilities);
    }

    private void initializeWorkspaceService(InitializeParams params, ServerCapabilities serverCapabilities) {
        this.workspaceService.initialize(params, serverCapabilities);
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
        this.workspaceService.stop();
        this.textDocumentService.stop();

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
    }
}
