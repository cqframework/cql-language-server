package org.opencds.cqf.cql.ls.server.plugin;

import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.opencds.cqf.cql.ls.server.manager.CqlTranslationManager;

public interface CqlLanguageServerPluginFactory {
    CqlLanguageServerPlugin createPlugin(CompletableFuture<LanguageClient> client, WorkspaceService workspaceService,
            TextDocumentService textDocumentService, CqlTranslationManager translationManager);
}