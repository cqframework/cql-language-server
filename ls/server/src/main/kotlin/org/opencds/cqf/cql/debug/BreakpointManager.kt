package org.opencds.cqf.cql.debug

import org.cqframework.cql.cql2elm.CqlCompiler
import org.cqframework.cql.gen.cqlParser
import org.eclipse.lsp4j.debug.Breakpoint
import org.eclipse.lsp4j.debug.BreakpointEventArguments
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.ls.core.ContentService
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
        // Use Path.equals() instead of URI.equals() so that on Windows the comparison
        // is case-insensitive.  This handles the drive-letter case mismatch between URIs
        // produced by File.toURI() (uppercase C:) and paths sent by VS Code (lowercase c:).
        // toAbsolutePath() is required because Paths.get("/unix/style") on Windows produces
        // a drive-relative path (\unix\style) while Paths.get(URI) is always fully absolute.
        return try {
            val inputPath = Paths.get(path).toAbsolutePath()
            librarySourceMap.entries.firstOrNull { (_, uri) ->
                if ("file" == uri.scheme) {
                    try {
                        Paths.get(uri) == inputPath
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    false
                }
            }?.key
        } catch (_: Exception) {
            null
        }
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

    fun updateBreakpointLineVerification(
        parseTree: cqlParser.LibraryContext,
        client: IDebugProtocolClient,
        breakpointIdsByPath: Map<String, Map<Int, Int>>,
    ) {
        val breakpointableLines = CqlStepPositionCollector.collectBreakpointableLines(parseTree)
        for ((sourcePath, lineToId) in breakpointIdsByPath) {
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
        compilationManager: org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager,
        librarySourceMap: Map<String, URI>,
    ): Set<Int>? {
        return runCatching {
            val treeUri =
                resolveLibraryIdFromPath(sourcePath, librarySourceMap)?.let { librarySourceMap[it] }
                    ?: if (sourcePath != null) Paths.get(sourcePath).toUri() else null
            treeUri?.let { compilationManager.getParseTree(it) }
                ?.let { CqlStepPositionCollector.collectBreakpointableLines(it) }
        }.getOrNull()
    }

    fun resolveSource(
        libraryId: String,
        streamingLaunchUri: String?,
        streamingHandler: StreamingBreakpointHandler?,
        librarySourceMap: Map<String, URI>,
        sourceReferenceRegistry: Map<Int, VersionedIdentifier>,
    ): Source {
        val uri = librarySourceMap[libraryId]
        return Source().also { s ->
            if (uri != null && uri.scheme == "file") {
                log.debug("resolveSource: libraryId={} branch=file path={}", libraryId, uri)
                s.path = Paths.get(uri).toString()
            } else if (streamingLaunchUri != null && uri == null &&
                (libraryId.isEmpty() || libraryId == streamingHandler?.primaryLibraryId)
            ) {
                log.debug("resolveSource: libraryId={} branch=streamingLaunchUri path={}", libraryId, streamingLaunchUri)
                s.path = Paths.get(URI.create(streamingLaunchUri)).toString()
            } else {
                // The ref is already encoded in `uri` (minted as "cql-source://$ref" at
                // registration time, CqlDebugServer.kt's onLibraryEnteredCallback) — read it
                // back directly rather than re-deriving it via an identifier-equality scan,
                // which can silently miss the matching entry and fall back to a bogus `0`
                // (a real, but wrong, registry key), producing empty DAP `source()` content.
                val ref =
                    uri?.takeIf { it.scheme == "cql-source" }?.host?.toIntOrNull()
                        ?: sourceReferenceRegistry.entries.firstOrNull { (_, id) -> id.id == libraryId }?.key
                s.sourceReference = ref ?: 0
                // Always label the source so an unresolved library shows a legible
                // placeholder tab instead of a bare "." title in the editor.
                s.name = if (libraryId.isNotEmpty()) "$libraryId.cql" else "unknown"
                log.debug(
                    "resolveSource: libraryId={} branch=sourceReference uri={} ref={} name={}",
                    libraryId,
                    uri,
                    s.sourceReference,
                    s.name,
                )
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
