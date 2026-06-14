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
import java.net.URI
import java.nio.file.Paths

class BreakpointManager(
    private val contentService: ContentService,
) {
    fun resolveLibraryIdFromPath(
        path: String?,
        librarySourceMap: Map<String, URI>,
    ): String? {
        if (path == null) return null
        val pathUri = Paths.get(path).toUri()
        return librarySourceMap.entries.firstOrNull { (_, uri) -> uri == pathUri }?.key
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

    fun collectTransitiveIncludes(
        primaryId: String,
        compiler: CqlCompiler?,
        streamingLaunchUri: String?,
        librarySourceMap: MutableMap<String, URI>,
    ): Set<String> {
        val result = mutableSetOf(primaryId)
        val visited = mutableSetOf(primaryId)
        val queue = ArrayDeque<org.hl7.elm.r1.IncludeDef>()
        compiler?.compiledLibrary?.library?.includes?.def?.forEach { queue.addLast(it) }

        while (queue.isNotEmpty()) {
            val includeDef = queue.removeFirst()
            val libPath = includeDef.path ?: continue
            if (libPath in visited) continue
            visited.add(libPath)

            val uri = librarySourceMap[libPath]
            if (uri == null) {
                try {
                    val identifier =
                        VersionedIdentifier().also { vi ->
                            vi.id = includeDef.path
                            vi.version = includeDef.version
                        }
                    val uris = contentService.locate(URI.create(streamingLaunchUri ?: ""), identifier)
                    val resolvedUri = uris.firstOrNull()
                    if (resolvedUri != null) {
                        librarySourceMap[libPath] = resolvedUri
                    }
                } catch (_: Exception) {
                }
            }
            result.add(libPath)
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
                s.path = Paths.get(uri).toString()
            } else if (streamingLaunchUri != null && uri == null &&
                (libraryId.isEmpty() || libraryId == streamingHandler?.primaryLibraryId)
            ) {
                s.path = Paths.get(URI.create(streamingLaunchUri)).toString()
            } else {
                val ref =
                    sourceReferenceRegistry.entries
                        .firstOrNull { (_, id) -> id.id == libraryId }?.key
                s.sourceReference = ref ?: 0
            }
        }
    }

    fun resolveFrameLibraryId(streamingHandler: StreamingBreakpointHandler?): String {
        return streamingHandler?.lastPausedCallStack?.firstOrNull()?.libraryId
            ?: streamingHandler?.primaryLibraryId
            ?: ""
    }
}
