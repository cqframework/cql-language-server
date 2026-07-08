package org.opencds.cqf.cql.ls.server.command

import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.IgIniDiagnosticsService
import java.util.concurrent.CompletableFuture

class RefreshIgDiagnosticsCommandContributionTest {
    private class CountingLibraryResolutionManager : LibraryResolutionManager(emptyList()) {
        var findIgIniIssuesCallCount = 0
            private set

        override fun findIgIniIssues(): List<LibraryResolutionManager.IgIniIssue> {
            findIgIniIssuesCallCount++
            return emptyList()
        }
    }

    @Test
    fun `getCommands returns refreshIgDiagnostics command`() {
        val manager = CountingLibraryResolutionManager()
        val service = IgIniDiagnosticsService(CompletableFuture.completedFuture(Mockito.mock(LanguageClient::class.java)), manager)
        val contribution = RefreshIgDiagnosticsCommandContribution(service)

        assertEquals(setOf("org.opencds.cqf.cql.ls.refreshIgDiagnostics"), contribution.getCommands())
    }

    @Test
    fun `executeCommand triggers a workspace re-scan`() {
        val manager = CountingLibraryResolutionManager()
        val service = IgIniDiagnosticsService(CompletableFuture.completedFuture(Mockito.mock(LanguageClient::class.java)), manager)
        val contribution = RefreshIgDiagnosticsCommandContribution(service)

        val result = contribution.executeCommand(ExecuteCommandParams("org.opencds.cqf.cql.ls.refreshIgDiagnostics", emptyList())).join()

        assertEquals(null, result)
        assertEquals(1, manager.findIgIniIssuesCallCount)
    }

    @Test
    fun `executeCommand defers unknown commands to super`() {
        val manager = CountingLibraryResolutionManager()
        val service = IgIniDiagnosticsService(CompletableFuture.completedFuture(Mockito.mock(LanguageClient::class.java)), manager)
        val contribution = RefreshIgDiagnosticsCommandContribution(service)

        assertThrows(RuntimeException::class.java) {
            contribution.executeCommand(ExecuteCommandParams("some.other.command", emptyList()))
        }
        assertEquals(0, manager.findIgIniIssuesCallCount)
    }
}
