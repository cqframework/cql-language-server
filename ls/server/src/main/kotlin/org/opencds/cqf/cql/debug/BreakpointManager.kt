package org.opencds.cqf.cql.debug

import org.cqframework.cql.cql2elm.CqlCompiler
import org.eclipse.lsp4j.debug.Breakpoint
import org.eclipse.lsp4j.debug.BreakpointEventArguments
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.VirtualLibraryUris
import org.opencds.cqf.cql.ls.server.visitor.CqlStepPositionCollector
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Paths

class BreakpointManager(
    private val contentService: ContentService,
) {
    companion object {
        private val log = LoggerFactory.getLogger(BreakpointManager::class.java)
    }

    fun resolveLibraryIdFromPath(
        path: String?,
        librarySourceMap: Map<String, URI>,
    ): String? {
        if (path == null) return null
        return librarySourceMap.entries.firstOrNull { (_, uri) -> sourcePathMatches(path, uri) }?.key
    }

    /**
     * True when a DAP `Source.path` refers to [uri].
     *
     * `cql-virtual` URIs (libraries with no workspace file) are compared as normalized URIs, since
     * VS Code sends them back as `cql-virtual:/X.cql` rather than the `cql-virtual:///X.cql` we mint.
     *
     * `file` URIs use Path.equals() instead of URI.equals() so that on Windows the comparison
     * is case-insensitive.  This handles the drive-letter case mismatch between URIs
     * produced by File.toURI() (uppercase C:) and paths sent by VS Code (lowercase c:).
     * toAbsolutePath() is required because Paths.get("/unix/style") on Windows produces
     * a drive-relative path (\unix\style) while Paths.get(URI) is always fully absolute.
     */
    fun sourcePathMatches(
        path: String,
        uri: URI,
    ): Boolean =
        try {
            when {
                VirtualLibraryUris.isVirtual(uri) -> VirtualLibraryUris.parse(path) == uri
                "file" == uri.scheme && !VirtualLibraryUris.looksLikeUri(path) ->
                    Paths.get(uri).toAbsolutePath() == Paths.get(path).toAbsolutePath()
                else -> false
            }
        } catch (_: Exception) {
            false
        }

    fun isRelevantSourcePath(
        sourcePath: String?,
        librarySourceMap: Map<String, URI>,
        relevantLibraryIds: Set<String>?,
    ): Boolean {
        val relevant = relevantLibraryIds ?: return true
        val libId = resolveLibraryIdFromPath(sourcePath, librarySourceMap)
        return libId != null && libId in relevant
    }

    /**
     * Returns every library id reachable from the primary library, at any include depth.
     *
     * `compiler.libraryManager.compiledLibraries` already holds the full transitive closure —
     * cql2elm must resolve and compile every included library (and its own includes, recursively)
     * to translate the primary library in the first place — so we read that closure directly
     * instead of re-walking `IncludeDef`s ourselves (which previously only reached direct includes,
     * leaving anything included by an include unresolved and permanently unverified).
     */
    fun collectTransitiveIncludes(
        primaryId: String,
        compiler: CqlCompiler?,
        streamingLaunchUri: String?,
        librarySourceMap: MutableMap<String, URI>,
        compilationManager: CqlCompilationManager? = null,
    ): Set<String> {
        val result = mutableSetOf(primaryId)
        val compiledLibraries = compiler?.libraryManager?.compiledLibraries ?: return result
        for (identifier in compiledLibraries.keys) {
            val libId = identifier.id ?: continue
            result.add(libId)
            if (librarySourceMap.containsKey(libId)) continue
            try {
                val resolvedUri = contentService.locate(URI.create(streamingLaunchUri ?: ""), identifier).firstOrNull()
                if (resolvedUri != null) {
                    librarySourceMap[libId] = resolvedUri
                } else if (compilationManager != null) {
                    // No workspace file (bundled / npm library): map it to the same cql-virtual
                    // URI go-to-definition uses, so breakpoints set in that tab resolve.
                    VirtualLibraryUris.ensureCompiled(compilationManager, compiler?.libraryManager, identifier)
                        ?.let { librarySourceMap[libId] = it }
                }
            } catch (_: Exception) {
            }
        }
        return result
    }

    fun updateBreakpointVerification(
        client: IDebugProtocolClient,
        breakpointIdsByPath: Map<String, Map<Int, Int>>,
        librarySourceMap: Map<String, URI>,
        relevantLibraryIds: Set<String>?,
    ) {
        val relevant = relevantLibraryIds ?: return
        for ((sourcePath, lineToId) in breakpointIdsByPath) {
            val libId = resolveLibraryIdFromPath(sourcePath, librarySourceMap)
            val isRelevant = libId != null && libId in relevant
            if (isRelevant) continue
            for ((line, id) in lineToId) {
                client.breakpoint(
                    BreakpointEventArguments().also {
                        it.reason = "changed"
                        it.breakpoint =
                            Breakpoint().also { bp ->
                                bp.id = id
                                bp.isVerified = false
                                bp.line = line
                                bp.source = Source().also { s -> s.path = sourcePath }
                            }
                    },
                )
            }
        }
    }

    /**
     * Marks breakpoints on lines that aren't breakpointable as unverified. Each source path is
     * checked against its own library's parse tree; paths that don't resolve to a library yet are
     * left alone (their relevance is handled by [updateBreakpointVerification]).
     */
    fun updateBreakpointLineVerification(
        client: IDebugProtocolClient,
        breakpointIdsByPath: Map<String, Map<Int, Int>>,
        librarySourceMap: Map<String, URI>,
        compilationManager: CqlCompilationManager,
    ) {
        for ((sourcePath, lineToId) in breakpointIdsByPath) {
            val uri = resolveLibraryIdFromPath(sourcePath, librarySourceMap)?.let { librarySourceMap[it] } ?: continue
            val parseTree = runCatching { compilationManager.getParseTree(uri) }.getOrNull() ?: continue
            val breakpointableLines = CqlStepPositionCollector.collectBreakpointableLines(parseTree)
            for ((line, id) in lineToId) {
                if (line !in breakpointableLines) {
                    client.breakpoint(
                        BreakpointEventArguments().also {
                            it.reason = "changed"
                            it.breakpoint =
                                Breakpoint().also { bp ->
                                    bp.id = id
                                    bp.isVerified = false
                                    bp.line = line
                                    bp.source = Source().also { s -> s.path = sourcePath }
                                }
                        },
                    )
                }
            }
        }
    }

    fun applyBreakpointableLinesFilter(
        sourcePath: String?,
        streamingLaunchUri: String?,
        compilationManager: CqlCompilationManager,
        librarySourceMap: Map<String, URI>,
    ): Set<Int>? {
        return runCatching {
            val treeUri =
                resolveLibraryIdFromPath(sourcePath, librarySourceMap)?.let { librarySourceMap[it] }
                    ?: when {
                        sourcePath == null -> null
                        // Never Paths.get() a URI-shaped path; an unmapped virtual path just has
                        // no parse tree (yet).
                        VirtualLibraryUris.looksLikeUri(sourcePath) -> VirtualLibraryUris.parse(sourcePath)
                        else -> Paths.get(sourcePath).toUri()
                    }
            treeUri?.let { compilationManager.getParseTree(it) }
                ?.let { CqlStepPositionCollector.collectBreakpointableLines(it) }
        }.getOrNull()
    }

    fun resolveSource(
        libraryId: String,
        streamingLaunchUri: String?,
        streamingHandler: StreamingBreakpointHandler?,
        librarySourceMap: Map<String, URI>,
    ): Source {
        val uri = librarySourceMap[libraryId]
        return Source().also { s ->
            if (uri != null && VirtualLibraryUris.isVirtual(uri)) {
                log.debug("resolveSource: libraryId={} branch=virtual path={}", libraryId, uri)
                s.path = uri.toString()
                s.name = uri.path.substringAfterLast('/')
            } else if (uri != null && uri.scheme == "file") {
                log.debug("resolveSource: libraryId={} branch=file path={}", libraryId, uri)
                s.path = Paths.get(uri).toString()
            } else if (streamingLaunchUri != null && uri == null &&
                (libraryId.isEmpty() || libraryId == streamingHandler?.primaryLibraryId)
            ) {
                log.debug("resolveSource: libraryId={} branch=streamingLaunchUri path={}", libraryId, streamingLaunchUri)
                s.path = Paths.get(URI.create(streamingLaunchUri)).toString()
            } else {
                // Always label the source so an unresolved library shows a legible
                // placeholder tab instead of a bare "." title in the editor.
                s.sourceReference = 0
                s.name = if (libraryId.isNotEmpty()) "$libraryId.cql" else "unknown"
                log.debug("resolveSource: libraryId={} branch=unresolved uri={} name={}", libraryId, uri, s.name)
            }
        }
    }

    fun resolveFrameLibraryId(streamingHandler: StreamingBreakpointHandler?): String {
        // lastPausedCallStack is stored outermost-first (oldest push first), so the innermost
        // (currently-executing) frame is the LAST entry — matching buildCqlStackFrames' convention.
        return streamingHandler?.lastPausedCallStack?.lastOrNull()?.libraryId
            ?: streamingHandler?.primaryLibraryId
            ?: ""
    }
}
