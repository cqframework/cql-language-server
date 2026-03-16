package org.opencds.cqf.cql.ls.plugin.debug

import com.google.auto.service.AutoService
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.plugin.CqlLanguageServerPlugin
import org.opencds.cqf.cql.ls.server.plugin.CqlLanguageServerPluginFactory
import java.util.concurrent.CompletableFuture

@AutoService(CqlLanguageServerPluginFactory::class)
class DebugPluginFactory : CqlLanguageServerPluginFactory {

    override fun createPlugin(
        client: CompletableFuture<LanguageClient>,
        workspaceService: WorkspaceService,
        textDocumentService: TextDocumentService,
        compilationManager: CqlCompilationManager
    ): CqlLanguageServerPlugin {
        return DebugPlugin(client, workspaceService, textDocumentService, compilationManager)
    }
}
