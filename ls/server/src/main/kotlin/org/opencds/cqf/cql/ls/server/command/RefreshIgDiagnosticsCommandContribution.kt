package org.opencds.cqf.cql.ls.server.command

import org.eclipse.lsp4j.ExecuteCommandParams
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution
import org.opencds.cqf.cql.ls.server.service.IgIniDiagnosticsService
import java.util.concurrent.CompletableFuture

/**
 * Manual fallback for [IgIniDiagnosticsService.validateWorkspace] — a safety net independent of
 * file-watcher coverage (e.g. an ig.ini `ig=` path outside the conventional
 * `input/ig.json`/`ImplementationGuide-*.json` patterns the workspace watchers cover).
 */
class RefreshIgDiagnosticsCommandContribution(
    private val igIniDiagnosticsService: IgIniDiagnosticsService,
) : CommandContribution {
    companion object {
        const val REFRESH_IG_DIAGNOSTICS_COMMAND = "org.opencds.cqf.cql.ls.refreshIgDiagnostics"
    }

    override fun getCommands(): Set<String> = setOf(REFRESH_IG_DIAGNOSTICS_COMMAND)

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        return if (REFRESH_IG_DIAGNOSTICS_COMMAND == params.command) {
            igIniDiagnosticsService.validateWorkspace()
            CompletableFuture.completedFuture(null)
        } else {
            super.executeCommand(params)
        }
    }
}
