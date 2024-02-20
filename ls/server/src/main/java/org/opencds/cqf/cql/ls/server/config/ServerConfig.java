package org.opencds.cqf.cql.ls.server.config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.services.LanguageClient;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Logger.JavaLogger;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.server.CqlLanguageServer;
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager;
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager;
import org.opencds.cqf.cql.ls.server.manager.IgContextManager;
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution;
import org.opencds.cqf.cql.ls.server.provider.FormattingProvider;
import org.opencds.cqf.cql.ls.server.provider.HoverProvider;
import org.opencds.cqf.cql.ls.server.service.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(PluginConfig.class)
public class ServerConfig {

    @Bean(name = "fileContentService")
    public ContentService fileContentService(List<WorkspaceFolder> workspaceFolders) {
        return new FileContentService(workspaceFolders);
    }

    @Bean(name = {"activeContentService"})
    public ActiveContentService activeContentService(EventBus eventBus) {
        ActiveContentService ac = new ActiveContentService();

        eventBus.register(ac);

        return ac;
    }

    @Bean(name = {"federatedContentService"})
    public FederatedContentService federatedContentService(
            ActiveContentService activeContentService, ContentService fileContentService) {
        return new FederatedContentService(activeContentService, fileContentService);
    }

    @Bean
    public CqlLanguageServer cqlLanguageServer(
            CompletableFuture<LanguageClient> languageClient,
            CqlWorkspaceService cqlWorkspaceService,
            CqlTextDocumentService cqlTextDocumentService) {
        return new CqlLanguageServer(languageClient, cqlWorkspaceService, cqlTextDocumentService);
    }

    @Bean
    public List<WorkspaceFolder> workspaceFolders() {
        return new ArrayList<>();
    }

    @Bean
    public CompletableFuture<LanguageClient> languageClient() {
        return new CompletableFuture<>();
    }

    @Bean
    public CqlTextDocumentService cqlTextDocumentService(
            CompletableFuture<LanguageClient> languageClient,
            HoverProvider hoverProvider,
            FormattingProvider formattingProvider,
            EventBus eventBus) {
        return new CqlTextDocumentService(languageClient, hoverProvider, formattingProvider, eventBus);
    }

    @Bean
    CqlWorkspaceService cqlWorkspaceService(
            CompletableFuture<LanguageClient> languageClient,
            CompletableFuture<List<CommandContribution>> commandContributions,
            List<WorkspaceFolder> workspaceFolders,
            EventBus eventBus) {
        return new CqlWorkspaceService(languageClient, commandContributions, workspaceFolders, eventBus);
    }

    @Bean
    CompilerOptionsManager compilerOptionsManager(ContentService contentService, EventBus eventBus) {
        CompilerOptionsManager t = new CompilerOptionsManager(contentService);

        eventBus.register(t);

        return t;
    }

    @Bean
    IgContextManager igContextManager(ContentService contentService, EventBus eventBus) {
        IgContextManager i = new IgContextManager(contentService);

        eventBus.register(i);

        return i;
    }

    @Bean
    CqlCompilationManager cqlCompilationManager(
            FederatedContentService federatedContentService,
            CompilerOptionsManager compilerOptionsManager,
            IgContextManager igContextManager) {
        return new CqlCompilationManager(federatedContentService, compilerOptionsManager, igContextManager);
    }

    @Bean
    HoverProvider hoverProvider(CqlCompilationManager cqlCompilationManager) {
        return new HoverProvider(cqlCompilationManager);
    }

    @Bean
    FormattingProvider formattingProvider(FederatedContentService contentService) {
        return new FormattingProvider(contentService);
    }

    @Bean
    DiagnosticsService diagnosticsService(
            CompletableFuture<LanguageClient> languageClient,
            CqlCompilationManager cqlCompilationManager,
            FederatedContentService contentService,
            EventBus eventBus) {
        DiagnosticsService ds = new DiagnosticsService(languageClient, cqlCompilationManager, contentService);

        eventBus.register(ds);

        return ds;
    }

    @Bean
    EventBus eventBus() {
        return EventBus.builder().logger(new JavaLogger("eventBus")).build();
    }
}
