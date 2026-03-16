package org.opencds.cqf.cql.ls.plugin.debug

import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution
import org.opencds.cqf.cql.ls.server.plugin.CqlLanguageServerPlugin
import java.util.concurrent.CompletableFuture

@Suppress("unused") // TODO: Remove once this class is completed
class DebugPlugin(
    private val client: CompletableFuture<LanguageClient>,
    private val workspaceService: WorkspaceService,
    private val textDocumentService: TextDocumentService,
    private val compilationManager: CqlCompilationManager
) : CqlLanguageServerPlugin {

    override fun getName(): String {
        return "org.opencds.cqf.cql.ls.plugin.debug.DebugPlugin"
    }

    private val commandContribution: CommandContribution = DebugCommandContribution(this.compilationManager)

    override fun getCommandContribution(): CommandContribution {
        return this.commandContribution
    }
}
