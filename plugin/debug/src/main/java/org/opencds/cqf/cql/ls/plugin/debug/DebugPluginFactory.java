package org.opencds.cqf.cql.ls.plugin.debug;

import com.google.auto.service.AutoService;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager;
import org.opencds.cqf.cql.ls.server.plugin.CqlLanguageServerPlugin;
import org.opencds.cqf.cql.ls.server.plugin.CqlLanguageServerPluginFactory;

import java.util.concurrent.CompletableFuture;

@AutoService(CqlLanguageServerPluginFactory.class)
public class DebugPluginFactory implements CqlLanguageServerPluginFactory {

    @Override
    public CqlLanguageServerPlugin createPlugin(CompletableFuture<LanguageClient> client,
            WorkspaceService workspaceService, TextDocumentService textDocumentService,
            CqlCompilationManager compilationManager) {
        return new DebugPlugin(client, workspaceService, textDocumentService, compilationManager);
    }
}
