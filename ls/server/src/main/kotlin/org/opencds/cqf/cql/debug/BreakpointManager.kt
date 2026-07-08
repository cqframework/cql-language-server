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

            // ELM stores namespace-qualified includes as a canonical URL path
            // (e.g. "http://smiledigitalhealth.com/PolicyStatusCommon"). Split into
            // system + local name so locate() can use the namespace fast-path, and
            // normalize the key to local name to match what the engine reports as libId.
            val (libSystem, libLocalName) =
                if (libPath.startsWith("http://") || libPath.startsWith("https://")) {
                    val slash = libPath.lastIndexOf('/')
                    if (slash > 0) libPath.substring(0, slash) to libPath.substring(slash + 1)
                    else null to libPath
                } else {
                    null to libPath
                }
            val libraryId = libLocalName

            if (!librarySourceMap.containsKey(libraryId)) {
                try {
                    val identifier =
                        VersionedIdentifier().also { vi ->
                            vi.id = libLocalName
                            vi.system = libSystem
                            vi.version = includeDef.version
                        }
                    // resolve(".") strips the filename to give the input/cql/ directory;
                    // locate() needs a directory root so BFS can scan for sibling libraries.
                    val locateRoot =
                        runCatching { URI.create(streamingLaunchUri ?: "").resolve(".") }
                            .getOrElse { URI.create("") }
                    val uris = contentService.locate(locateRoot, identifier)
                    val resolvedUri = uris.firstOrNull()
                    if (resolvedUri != null) {
                        librarySourceMap[libraryId] = resolvedUri
                    } else {
                        log.debug(
                            "collectTransitiveIncludes: could not locate '{}' (system='{}') — breakpoints in this library may not be verified",
                            libLocalName,
                            libSystem,
                        )
                    }
                } catch (e: Exception) {
                    log.warn("collectTransitiveIncludes: failed to locate '{}': {}", libLocalName, e.message)
                }
            }
            result.add(libraryId)
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
                val entry =
                    sourceReferenceRegistry.entries
                        .firstOrNull { (_, id) -> id.id == libraryId }
                s.sourceReference = entry?.key ?: 0
                // Name the virtual document so the editor tab shows the library name
                // rather than '.' (VS Code's fallback when name is null and path is absent).
                val identifier = entry?.value
                s.name =
                    if (identifier?.version != null) {
                        "${identifier.id}-${identifier.version}.cql"
                    } else if (!libraryId.isEmpty()) {
                        "$libraryId.cql"
                    } else {
                        null
                    }
            }
        }
    }

    fun resolveFrameLibraryId(streamingHandler: StreamingBreakpointHandler?): String {
        return streamingHandler?.lastPausedCallStack?.firstOrNull()?.libraryId
            ?: streamingHandler?.primaryLibraryId
            ?: ""
    }
}
