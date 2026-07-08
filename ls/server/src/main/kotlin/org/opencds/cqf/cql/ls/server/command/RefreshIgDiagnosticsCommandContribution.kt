package org.opencds.cqf.cql.ls.server.command

import org.eclipse.lsp4j.ExecuteCommandParams
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution
import org.opencds.cqf.cql.ls.server.service.DiagnosticsService
import org.opencds.cqf.cql.ls.server.service.IgIniDiagnosticsService
import java.util.concurrent.CompletableFuture

/**
 * Manual refresh command that resets all cached IG state from scratch and triggers
 * recompilation of previously-opened CQL files. Use as a safety net when:
 * - The file-watcher-based automatic refresh didn't fire (e.g. ig.ini `ig=` path outside
 *   the conventional `input/ig.json`/`ImplementationGuide-*.json` patterns).
 * - The user has edited an IG resource file and wants recompilation to pick up changes
 *   without closing/reopening the workspace.
 */
class RefreshIgDiagnosticsCommandContribution(
    private val igContextManager: IgContextManager,
    private val libraryResolutionManager: LibraryResolutionManager,
    private val igIniDiagnosticsService: IgIniDiagnosticsService,
    private val diagnosticsService: DiagnosticsService,
) : CommandContribution {
    companion object {
        const val REFRESH_IG_DIAGNOSTICS_COMMAND = "org.opencds.cqf.cql.ls.refreshIgDiagnostics"
    }

    override fun getCommands(): Set<String> = setOf(REFRESH_IG_DIAGNOSTICS_COMMAND)

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        return if (REFRESH_IG_DIAGNOSTICS_COMMAND == params.command) {
            igContextManager.clearAllContexts()
            libraryResolutionManager.invalidateNamespaceIndex()
            igIniDiagnosticsService.validateWorkspace()
            diagnosticsService.refreshAll()
            CompletableFuture.completedFuture(null)
        } else {
            super.executeCommand(params)
        }
    }
}
