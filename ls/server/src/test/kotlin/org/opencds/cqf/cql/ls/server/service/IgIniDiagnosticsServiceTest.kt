package org.opencds.cqf.cql.ls.server.service

import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import java.net.URI
import java.util.concurrent.CompletableFuture

class IgIniDiagnosticsServiceTest {
    /** LibraryResolutionManager stub that returns a fixed, mutable list of issues. */
    private class StubLibraryResolutionManager : LibraryResolutionManager(emptyList()) {
        var issues: List<LibraryResolutionManager.IgIniIssue> = emptyList()

        override fun findIgIniIssues(): List<LibraryResolutionManager.IgIniIssue> = issues
    }

    private fun service(
        client: LanguageClient = Mockito.mock(LanguageClient::class.java),
        manager: StubLibraryResolutionManager = StubLibraryResolutionManager(),
    ): Pair<IgIniDiagnosticsService, StubLibraryResolutionManager> {
        val svc = IgIniDiagnosticsService(CompletableFuture.completedFuture(client), manager)
        return svc to manager
    }

    @Test
    fun validateWorkspace_noIssues_publishesNothing() {
        val client = Mockito.mock(LanguageClient::class.java)
        val (svc, _) = service(client)

        svc.validateWorkspace()

        Mockito.verify(client, Mockito.never()).publishDiagnostics(Mockito.any())
        Mockito.verify(client, Mockito.never()).showMessage(Mockito.any())
    }

    @Test
    fun validateWorkspace_missingPackageId_publishesWarningDiagnosticAndToast() {
        val client = Mockito.mock(LanguageClient::class.java)
        val igIniUri = URI.create("file:///workspace/ProjectA/ig.ini")
        val (svc, manager) = service(client)
        manager.issues = listOf(LibraryResolutionManager.IgIniIssue(igIniUri, listOf("packageId")))

        svc.validateWorkspace()

        val diagnosticsCaptor = ArgumentCaptor.forClass(PublishDiagnosticsParams::class.java)
        Mockito.verify(client).publishDiagnostics(diagnosticsCaptor.capture())
        val published = diagnosticsCaptor.value
        assertEquals(1, published.diagnostics.size)
        assertEquals(DiagnosticSeverity.Warning, published.diagnostics[0].severity)
        assertTrue(published.diagnostics[0].message.contains("packageId"))

        Mockito.verify(client).showMessage(Mockito.any(MessageParams::class.java))
    }

    @Test
    fun validateWorkspace_sameIssuePersistsAcrossRescans_toastsOnlyOnce() {
        val client = Mockito.mock(LanguageClient::class.java)
        val igIniUri = URI.create("file:///workspace/ProjectA/ig.ini")
        val (svc, manager) = service(client)
        manager.issues = listOf(LibraryResolutionManager.IgIniIssue(igIniUri, listOf("packageId")))

        svc.validateWorkspace()
        svc.validateWorkspace()

        Mockito.verify(client, Mockito.times(1)).showMessage(Mockito.any())
        // Diagnostic re-published on every scan is fine — it's idempotent from the client's view.
        Mockito.verify(client, Mockito.times(2)).publishDiagnostics(Mockito.any())
    }

    @Test
    fun validateWorkspace_issueFixed_clearsDiagnostic() {
        val client = Mockito.mock(LanguageClient::class.java)
        val igIniUri = URI.create("file:///workspace/ProjectA/ig.ini")
        val (svc, manager) = service(client)
        manager.issues = listOf(LibraryResolutionManager.IgIniIssue(igIniUri, listOf("packageId")))
        svc.validateWorkspace()

        manager.issues = emptyList()
        svc.validateWorkspace()

        val diagnosticsCaptor = ArgumentCaptor.forClass(PublishDiagnosticsParams::class.java)
        Mockito.verify(client, Mockito.times(2)).publishDiagnostics(diagnosticsCaptor.capture())
        val clearingCall = diagnosticsCaptor.allValues[1]
        assertTrue(clearingCall.diagnostics.isEmpty())
    }
}
