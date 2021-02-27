package org.opencds.cqf.cql.ls.plugin.debug;

import java.util.concurrent.CompletableFuture;

import com.google.auto.service.AutoService;

import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.opencds.cqf.cql.ls.manager.CqlTranslationManager;
import org.opencds.cqf.cql.ls.plugin.CqlLanguageServerPlugin;
import org.opencds.cqf.cql.ls.plugin.CqlLanguageServerPluginFactory;

@AutoService(CqlLanguageServerPluginFactory.class)
public class DebugPluginFactory implements CqlLanguageServerPluginFactory {

    @Override
    public CqlLanguageServerPlugin createPlugin(CompletableFuture<LanguageClient> client, WorkspaceService workspaceService,
            TextDocumentService textDocumentService, CqlTranslationManager translationManager) {
        return new DebugPlugin(client, workspaceService, textDocumentService, translationManager);
    }
}
