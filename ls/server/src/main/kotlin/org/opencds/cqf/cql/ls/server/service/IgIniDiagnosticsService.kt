package org.opencds.cqf.cql.ls.server.service

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.services.LanguageClient
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Warns the user when a workspace project's ImplementationGuide resource (the one its ig.ini
 * points to) is missing `packageId` and/or `url`. Without these, other workspace projects that
 * declare a dependency on the project cannot resolve its CQL libraries — and previously failed
 * silently (see [LibraryResolutionManager.findIgIniIssues]).
 */
class IgIniDiagnosticsService(
    private val client: CompletableFuture<LanguageClient>,
    private val libraryResolutionManager: LibraryResolutionManager,
) {
    companion object {
        private const val SOURCE = "ig.ini"
    }

    // ig.ini files currently carrying a published diagnostic — used to clear it once fixed.
    private val activeIssues = ConcurrentHashMap.newKeySet<URI>()

    // ig.ini files already toasted this session — a warning fires once per file, not on every rescan.
    private val toastedOnce = ConcurrentHashMap.newKeySet<URI>()

    fun validateWorkspace() = validate(libraryResolutionManager.findIgIniIssues())

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onMessageEvent(event: DidChangeWatchedFilesEvent) {
        if (event.params().changes.any { it.uri.endsWith("ig.ini") }) {
            validate(libraryResolutionManager.findIgIniIssues())
        }
    }

    private fun validate(issues: List<LibraryResolutionManager.IgIniIssue>) {
        for (issue in issues) {
            publishDiagnostic(issue)
            activeIssues.add(issue.igIniUri)
            if (toastedOnce.add(issue.igIniUri)) {
                showToast(issue)
            }
        }

        val fixedUris = activeIssues - issues.map { it.igIniUri }.toSet()
        for (uri in fixedUris) {
            client.join().publishDiagnostics(PublishDiagnosticsParams(Uris.toClientUri(uri), emptyList()))
            activeIssues.remove(uri)
        }
    }

    private fun publishDiagnostic(issue: LibraryResolutionManager.IgIniIssue) {
        val diagnostic =
            Diagnostic(
                Range(Position(0, 0), Position(0, 0)),
                "The ImplementationGuide resource for this project is missing " +
                    "${issue.missingFields.joinToString(", ")}. Other workspace projects that depend on this " +
                    "one will not be able to resolve its CQL libraries.",
                DiagnosticSeverity.Warning,
                SOURCE,
            )
        client.join().publishDiagnostics(
            PublishDiagnosticsParams(Uris.toClientUri(issue.igIniUri), listOf(diagnostic)),
        )
    }

    private fun showToast(issue: LibraryResolutionManager.IgIniIssue) {
        client.join().showMessage(
            MessageParams(
                MessageType.Warning,
                "The ImplementationGuide resource referenced by ${issue.igIniUri.path} is missing " +
                    "${issue.missingFields.joinToString(", ")} — workspace projects that depend on it will not " +
                    "be able to resolve its CQL libraries.",
            ),
        )
    }
}
