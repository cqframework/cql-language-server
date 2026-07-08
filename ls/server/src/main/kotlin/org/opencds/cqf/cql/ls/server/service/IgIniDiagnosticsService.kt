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

        /**
         * Matches the ig.ini/ImplementationGuide-resource watcher globs registered in
         * [org.opencds.cqf.cql.ls.server.service.CqlWorkspaceService]'s `basicWatchers` — kept in
         * sync with those patterns so every watched IG file actually triggers a re-scan here.
         */
        internal fun isWatchedIgFile(uri: String): Boolean {
            val fileName = uri.substringAfterLast('/')
            return uri.endsWith("ig.ini") ||
                uri.endsWith("/input/ig.json") ||
                (fileName.startsWith("ImplementationGuide-") && fileName.endsWith(".json"))
        }
    }

    // Resource files currently carrying a published diagnostic — used to clear it once fixed.
    private val activeIssues = ConcurrentHashMap.newKeySet<URI>()

    // Resource files already toasted this session — a warning fires once per file, not on every rescan.
    private val toastedOnce = ConcurrentHashMap.newKeySet<URI>()

    fun validateWorkspace() = validate(libraryResolutionManager.findIgIniIssues())

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onMessageEvent(event: DidChangeWatchedFilesEvent) {
        if (event.params().changes.any { isWatchedIgFile(it.uri) }) {
            validate(libraryResolutionManager.findIgIniIssues())
        }
    }

    private fun validate(issues: List<LibraryResolutionManager.IgIniIssue>) {
        for (issue in issues) {
            publishDiagnostic(issue)
            activeIssues.add(issue.resourceUri)
            if (toastedOnce.add(issue.resourceUri)) {
                showToast(issue)
            }
        }

        val fixedUris = activeIssues - issues.map { it.resourceUri }.toSet()
        for (uri in fixedUris) {
            client.join().publishDiagnostics(PublishDiagnosticsParams(Uris.toClientUri(uri), emptyList()))
            activeIssues.remove(uri)
        }
    }

    private fun publishDiagnostic(issue: LibraryResolutionManager.IgIniIssue) {
        val diagnostic =
            Diagnostic(
                Range(Position(0, 0), Position(0, 0)),
                "This ImplementationGuide resource is missing ${issue.missingFields.joinToString(", ")}. " +
                    "It's the resource referenced by ${issue.igIniUri.path}'s 'ig=' setting — other workspace " +
                    "projects that depend on this one will not be able to resolve its CQL libraries.",
                DiagnosticSeverity.Warning,
                SOURCE,
            )
        client.join().publishDiagnostics(
            PublishDiagnosticsParams(Uris.toClientUri(issue.resourceUri), listOf(diagnostic)),
        )
    }

    private fun showToast(issue: LibraryResolutionManager.IgIniIssue) {
        client.join().showMessage(
            MessageParams(
                MessageType.Warning,
                "${issue.resourceUri.path} is missing ${issue.missingFields.joinToString(", ")} — it's the " +
                    "resource referenced by ${issue.igIniUri.path}'s 'ig=' setting, and workspace projects that " +
                    "depend on it will not be able to resolve its CQL libraries.",
            ),
        )
    }
}
