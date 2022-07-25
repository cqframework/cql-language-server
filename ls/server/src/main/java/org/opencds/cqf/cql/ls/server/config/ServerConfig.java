package org.opencds.cqf.cql.ls.server.config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.services.LanguageClient;
import org.greenrobot.eventbus.EventBus;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.server.CqlLanguageServer;
import org.opencds.cqf.cql.ls.server.manager.CqlTranslationManager;
import org.opencds.cqf.cql.ls.server.manager.TranslatorOptionsManager;
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution;
import org.opencds.cqf.cql.ls.server.provider.FormattingProvider;
import org.opencds.cqf.cql.ls.server.provider.HoverProvider;
import org.opencds.cqf.cql.ls.server.service.ActiveContentService;
import org.opencds.cqf.cql.ls.server.service.CqlTextDocumentService;
import org.opencds.cqf.cql.ls.server.service.CqlWorkspaceService;
import org.opencds.cqf.cql.ls.server.service.DiagnosticsService;
import org.opencds.cqf.cql.ls.server.service.FileContentService;
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

    @Bean(name = {"contentService", "activeContentService"})
    public ContentService activeContentService(ContentService fileContentService) {
        ActiveContentService ac = new ActiveContentService(fileContentService);

        EventBus.getDefault().register(ac);

        return ac;
    }

    @Bean
    public CqlLanguageServer cqlLanguageServer(CompletableFuture<LanguageClient> languageClient,
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
            CompletableFuture<LanguageClient> languageClient, HoverProvider hoverProvider,
            FormattingProvider formattingProvider) {
        return new CqlTextDocumentService(languageClient, hoverProvider, formattingProvider);
    }

    @Bean
    CqlWorkspaceService cqlWorkspaceService(CompletableFuture<LanguageClient> languageClient,
            CompletableFuture<List<CommandContribution>> commandContributions,
            List<WorkspaceFolder> workspaceFolders) {
        return new CqlWorkspaceService(languageClient, commandContributions, workspaceFolders);
    }

    @Bean
    TranslatorOptionsManager translatorOptionsManager(ContentService contentService) {
        TranslatorOptionsManager t = new TranslatorOptionsManager(contentService);

        EventBus.getDefault().register(t);

        return t;
    }

    @Bean
    CqlTranslationManager cqlTranslationManager(ContentService contentService,
            TranslatorOptionsManager translatorOptionsManager) {
        return new CqlTranslationManager(contentService, translatorOptionsManager);


    }

    @Bean
    HoverProvider hoverProvider(CqlTranslationManager cqlTranslationManager) {
        return new HoverProvider(cqlTranslationManager);
    }

    @Bean
    FormattingProvider formattingProvider(ContentService contentService) {
        return new FormattingProvider(contentService);
    }

    @Bean
    DiagnosticsService diagnosticsService(CompletableFuture<LanguageClient> languageClient,
            CqlTranslationManager cqlTranslationManager, ContentService contentService) {
        DiagnosticsService ds =
                new DiagnosticsService(languageClient, cqlTranslationManager, contentService);

        EventBus.getDefault().register(ds);

        return ds;
    }
}
