package org.opencds.cqf.cql.ls.server.service

import com.google.common.base.Joiner
import org.cqframework.cql.cql2elm.CqlCompilerException
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.services.LanguageClient
import org.greenrobot.eventbus.Subscribe
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.event.DidChangeTextDocumentEvent
import org.opencds.cqf.cql.ls.server.event.DidCloseTextDocumentEvent
import org.opencds.cqf.cql.ls.server.event.DidOpenTextDocumentEvent
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.utility.Diagnostics
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class DiagnosticsService(
    private val client: CompletableFuture<LanguageClient>,
    private val cqlCompilationManager: CqlCompilationManager,
    private val contentService: ContentService,
) {
    companion object {
        private val log = LoggerFactory.getLogger(DiagnosticsService::class.java)
        private const val BOUNCE_DELAY = 200L
    }

    private val executor =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "Debouncer").also { it.isDaemon = true }
        }

    private var future: ScheduledFuture<*>? = null

    protected fun doLint(paths: Collection<URI>?) {
        if (paths == null || paths.isEmpty()) return

        log.debug("Lint: {}", Joiner.on(", ").join(paths))

        val allDiagnostics = mutableMapOf<URI, MutableSet<Diagnostic>>()
        for (uri in paths) {
            val currentDiagnostics = lint(uri)
            log.debug("Merging Diagnostics: {}", uri)
            mergeDiagnostics(allDiagnostics, currentDiagnostics)
        }

        for ((uri, diagnostics) in allDiagnostics) {
            log.debug("Publishing {} Diagnostics for: {}", diagnostics.size, uri)
            val params = PublishDiagnosticsParams(Uris.toClientUri(uri), ArrayList(diagnostics))
            client.join().publishDiagnostics(params)
        }
    }

    private fun mergeDiagnostics(
        currentDiagnostics: MutableMap<URI, MutableSet<Diagnostic>>,
        newDiagnostics: Map<URI, Set<Diagnostic>>,
    ) {
        for ((uri, diagnostics) in newDiagnostics) {
            currentDiagnostics.getOrPut(uri) { mutableSetOf() }.addAll(diagnostics)
        }
    }

    fun lint(uri: URI): Map<URI, Set<Diagnostic>> {
        val diagnostics = mutableMapOf<URI, MutableSet<Diagnostic>>()
        val compiler = cqlCompilationManager.compile(uri)
        if (compiler == null) {
            val d =
                Diagnostic(
                    Range(Position(0, 0), Position(0, 0)),
                    "Library does not contain CQL content.",
                    DiagnosticSeverity.Warning,
                    "lint",
                )
            diagnostics.getOrPut(uri) { mutableSetOf() }.add(d)
            return diagnostics
        }

        val exceptions: List<CqlCompilerException> = compiler.exceptions

        log.debug("lint completed on {} with {} messages.", uri, exceptions.size)

        val uniqueLibraries: List<VersionedIdentifier> =
            exceptions
                .map { it?.locator?.library }
                .distinct()
                .filterNotNull()
                .filter { it.id != null }

        val root = Uris.getHead(uri)
        val libraryUris = mutableMapOf<VersionedIdentifier, URI>()

        for (libraryIdentifier in uniqueLibraries) {
            val uris = contentService.locate(root, libraryIdentifier)
            if (!uris.isNullOrEmpty()) {
                libraryUris[libraryIdentifier] = uris.iterator().next()
            } else {
                // The message is associated with a library loaded from outside the content service (e.g. an npm
                // library). So associate the message with the current uri
                libraryUris[libraryIdentifier] = uri
            }
        }

        // Map "unknown" libraries to the current uri
        libraryUris[VersionedIdentifier().withId("unknown")] = uri

        for (exception in exceptions) {
            val exLocator = exception.locator
            val exLibrary = exLocator?.library
            var eUri =
                if (exLibrary != null && exLibrary.id != null) {
                    libraryUris[exLibrary]
                } else {
                    null
                }

            if (eUri == null) {
                eUri = uri // put all unknown or indeterminate errors to the current uri so at least they get reported
            }

            val d = Diagnostics.convert(exception)

            if (d != null) {
                log.debug(
                    "diagnostic: {} {}:{}-{}:{}: {}",
                    eUri,
                    d.range.start.line,
                    d.range.start.character,
                    d.range.end.line,
                    d.range.end.character,
                    d.message,
                )

                diagnostics.getOrPut(eUri) { mutableSetOf() }.add(d)
            }
        }

        // Ensure there is an entry for the library in the case that there are no exceptions
        diagnostics.getOrPut(uri) { mutableSetOf() }

        return diagnostics
    }

    @Subscribe
    fun didOpen(e: DidOpenTextDocumentEvent) {
        log.debug("didOpen: {}", e.params().textDocument.uri)
        doLint(listOfNotNull(Uris.parseOrNull(e.params().textDocument.uri)))
    }

    @Subscribe
    fun didClose(e: DidCloseTextDocumentEvent) {
        log.debug("didClose: {}", e.params().textDocument.uri)
        val params = PublishDiagnosticsParams(e.params().textDocument.uri, ArrayList())
        client.join().publishDiagnostics(params)
    }

    @Subscribe
    fun didChange(e: DidChangeTextDocumentEvent) {
        log.debug("didChange: {}", e.params().textDocument.uri)
        debounce(BOUNCE_DELAY) {
            doLint(listOfNotNull(Uris.parseOrNull(e.params().textDocument.uri)))
        }
    }

    internal fun debounce(
        delay: Long,
        task: Runnable,
    ) {
        future?.takeIf { !it.isDone }?.cancel(false)
        future = executor.schedule(task, delay, TimeUnit.MILLISECONDS)
    }
}
