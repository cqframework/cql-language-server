package org.opencds.cqf.cql.ls.server.command

import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.DiagnosticsService
import org.opencds.cqf.cql.ls.server.service.IgIniDiagnosticsService
import java.util.concurrent.CompletableFuture

class RefreshIgDiagnosticsCommandContributionTest {
    private class CountingLibraryResolutionManager : LibraryResolutionManager(emptyList()) {
        var findIgIniIssuesCallCount = 0
            private set
        var invalidateCallCount = 0
            private set

        override fun findIgIniIssues(): List<LibraryResolutionManager.IgIniIssue> {
            findIgIniIssuesCallCount++
            return emptyList()
        }

        override fun invalidateNamespaceIndex() {
            invalidateCallCount++
        }
    }

    private fun igIniService(libraryResolutionManager: LibraryResolutionManager): IgIniDiagnosticsService =
        IgIniDiagnosticsService(CompletableFuture.completedFuture(Mockito.mock(LanguageClient::class.java)), libraryResolutionManager)

    @Test
    fun `getCommands returns refreshIgDiagnostics command`() {
        val contribution = RefreshIgDiagnosticsCommandContribution(
            Mockito.mock(IgContextManager::class.java),
            Mockito.mock(LibraryResolutionManager::class.java),
            igIniService(Mockito.mock(LibraryResolutionManager::class.java)),
            Mockito.mock(DiagnosticsService::class.java),
        )
        assertEquals(setOf("org.opencds.cqf.cql.ls.refreshIgDiagnostics"), contribution.getCommands())
    }

    @Test
    fun `executeCommand triggers full refresh`() {
        val igContextManager = Mockito.mock(IgContextManager::class.java)
        val manager = CountingLibraryResolutionManager()
        val diagnosticsService = Mockito.mock(DiagnosticsService::class.java)
        val contribution = RefreshIgDiagnosticsCommandContribution(
            igContextManager, manager, igIniService(manager), diagnosticsService,
        )

        val result = contribution.executeCommand(ExecuteCommandParams("org.opencds.cqf.cql.ls.refreshIgDiagnostics", emptyList())).join()

        assertEquals(null, result)
        Mockito.verify(igContextManager).clearAllContexts()
        assertEquals(1, manager.invalidateCallCount)
        assertEquals(1, manager.findIgIniIssuesCallCount)
        Mockito.verify(diagnosticsService).refreshAll()
    }

    @Test
    fun `executeCommand defers unknown commands to super`() {
        val contribution = RefreshIgDiagnosticsCommandContribution(
            Mockito.mock(IgContextManager::class.java),
            Mockito.mock(LibraryResolutionManager::class.java),
            igIniService(Mockito.mock(LibraryResolutionManager::class.java)),
            Mockito.mock(DiagnosticsService::class.java),
        )
        assertThrows(RuntimeException::class.java) {
            contribution.executeCommand(ExecuteCommandParams("some.other.command", emptyList()))
        }
    }
}
