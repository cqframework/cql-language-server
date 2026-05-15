package org.opencds.cqf.cql.ls.server.service

// NOTE: This implementation assumes library file names will always take the form:
// <filename>[-<version>].cql
import org.cqframework.cql.cql2elm.model.Version
import org.eclipse.lsp4j.WorkspaceFolder
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionConfig
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionConfigProvider
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionMode
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Paths

class FileContentService(
    protected val workspaceFolders: List<WorkspaceFolder>,
    private val configProvider: LibraryResolutionConfigProvider,
    private val namespaceManager: LibraryResolutionManager,
) : ContentService {
    companion object {
        private val log = LoggerFactory.getLogger(FileContentService::class.java)

        /**
         * Searches [directory] for a CQL file matching [libraryIdentifier], first by exact
         * versioned filename, then by BFS-compatible fallback. Returns null if not found.
         *
         * This companion method preserves the original public API used by external call sites
         * and existing tests.
         */
        fun searchFolder(
            directory: URI,
            libraryIdentifier: VersionedIdentifier,
        ): File? {
            val path =
                try {
                    Paths.get(directory)
                } catch (e: Exception) {
                    log.warn("error searching directory $directory. Skipping.", e)
                    return null
                }

            val libraryName = libraryIdentifier.id ?: return null
            val version = libraryIdentifier.version

            // Fast path: exact versioned or unversioned filename match
            val exactName = if (version != null) "$libraryName-$version.cql" else "$libraryName.cql"
            val exactFile = path.resolve(exactName).toFile()
            if (exactFile.exists()) return exactFile

            // Fallback: BFS-compatible search starting from this directory
            return bfsSingle(path.toFile(), libraryName, version)
        }

        /**
         * BFS search within a single directory tree, returning the best compatible match.
         * At each depth level, returns the newest compatible file among all candidates at
         * that level, preferring exact version matches (by [Version.compareTo]) over
         * patch-compatible ones.
         */
        private fun bfsSingle(
            root: File,
            name: String,
            version: String?,
        ): File? {
            if (!root.exists() || !root.isDirectory) return null
            val requestedVersion = version?.let { tryParseVersion(it) }
            var currentLevel = listOf(root)

            while (currentLevel.isNotEmpty()) {
                val nextLevel = mutableListOf<File>()
                var bestFile: File? = null
                var bestVersion: Version? = null

                for (dir in currentLevel) {
                    for (file in (dir.listFiles() ?: emptyArray())) {
                        if (file.isDirectory) {
                            nextLevel.add(file)
                            continue
                        }
                        if (!file.isFile || !file.name.endsWith(".cql")) continue
                        val (parsedName, parsedVersion) = getNameAndVersion(file.name)
                        if (!parsedName.equals(name, ignoreCase = true)) continue

                        when {
                            // Exact version match — return immediately
                            requestedVersion != null && parsedVersion != null &&
                                parsedVersion.compareTo(requestedVersion) == 0 -> return file
                            // Unversioned file — accept as any version
                            parsedVersion == null -> return file
                            // Compatible: keep newest at this depth
                            (requestedVersion == null || parsedVersion.compatibleWith(requestedVersion)) &&
                                (bestVersion == null || parsedVersion.compareTo(bestVersion) > 0) -> {
                                bestVersion = parsedVersion
                                bestFile = file
                            }
                        }
                    }
                }

                if (bestFile != null) return bestFile
                currentLevel = nextLevel
            }
            return null
        }

        private fun tryParseVersion(version: String): Version? =
            try {
                Version(version)
            } catch (_: Exception) {
                null
            }

        private fun getNameAndVersion(fileName: String): Pair<String, Version?> {
            var name = fileName
            val indexOfExtension = name.lastIndexOf(".")
            if (indexOfExtension >= 0) name = name.substring(0, indexOfExtension)

            val indexOfVersionSeparator = name.lastIndexOf("-")
            var version: Version? = null
            if (indexOfVersionSeparator >= 0) {
                version = tryParseVersion(name.substring(indexOfVersionSeparator + 1))
                if (version != null) name = name.substring(0, indexOfVersionSeparator)
            }
            return Pair(name, version)
        }
    }

    override fun locate(
        root: URI,
        identifier: VersionedIdentifier,
    ): Set<URI> {
        requireNotNull(root)
        requireNotNull(identifier)

        val name = identifier.id ?: return emptySet()

        // ── Namespace fast-path ─────────────────────────────────────────────────
        // identifier.system != null means a namespace-qualified include arrived
        // (e.g. `include com.example.helper100` → system="https://example.com/fhir")
        val identifierSystem = identifier.system
        if (identifierSystem != null) {
            val inputCqlUri = namespaceManager.resolveCanonicalUrl(identifierSystem)
                ?: return emptySet()  // unknown namespace — resolution error, not a crash
            val rootFile = toFile(inputCqlUri) ?: return emptySet()
            val version = identifier.version
            val file = (if (version != null) bfsExact(rootFile, name, version) else null)
                ?: bfsCompatible(rootFile, name, version, LibraryResolutionMode.PATCH_FLEXIBLE)
                ?: return emptySet()
            return setOf(file.toURI())
        }

        // ── Unqualified include — tiered search ─────────────────────────────────
        val containingFolderUri = findContainingFolder(root) ?: return emptySet()
        val config = configProvider.getConfig(root)
        log.debug(
            "locate: '{}' version '{}' — root={}, crossProjectSearch={}, mode={}",
            name, identifier.version, root, config.unqualifiedCrossProjectSearch, config.mode,
        )
        val version = identifier.version

        val tier1File = toFile(root) ?: return emptySet()

        val tier2Uri = Uris.addPath(containingFolderUri, "input")?.let { Uris.addPath(it, "cql") }
        val tier2Enabled = tier2Uri != null && tier2Uri != root
        val tier2File = if (tier2Enabled) tier2Uri?.let { toFile(it) } else null

        // Pass 1: exact version match (skipped when no version is specified)
        if (version != null) {
            bfsExact(tier1File, name, version)?.let { return setOf(it.toURI()) }
            if (tier2Enabled && tier2File != null) {
                bfsExact(tier2File, name, version)?.let { return setOf(it.toURI()) }
            }
            if (config.unqualifiedCrossProjectSearch) {
                for (folderUri in tier3FolderUris(containingFolderUri, config)) {
                    val inputCql =
                        Uris.addPath(folderUri, "input")?.let { Uris.addPath(it, "cql") }
                            ?: continue
                    bfsExact(toFile(inputCql) ?: continue, name, version)
                        ?.let { return setOf(it.toURI()) }
                }
            }
        }

        // Pass 2: compatible / any-version match
        // Skipped when a version was specified and mode is STRICT (exact-only, no fallback).
        if (version == null || config.mode != LibraryResolutionMode.STRICT) {
            bfsCompatible(tier1File, name, version, config.mode)
                ?.let { return setOf(it.toURI()) }
            if (tier2Enabled && tier2File != null) {
                bfsCompatible(tier2File, name, version, config.mode)
                    ?.let { return setOf(it.toURI()) }
            }
            if (config.unqualifiedCrossProjectSearch) {
                for (folderUri in tier3FolderUris(containingFolderUri, config)) {
                    val inputCql =
                        Uris.addPath(folderUri, "input")?.let { Uris.addPath(it, "cql") }
                            ?: continue
                    bfsCompatible(toFile(inputCql) ?: continue, name, version, config.mode)
                        ?.let { return setOf(it.toURI()) }
                }
            }
        }

        return emptySet()
    }

    override fun read(uri: URI): InputStream? {
        return try {
            BufferedInputStream(FileInputStream(File(uri)))
        } catch (e: Exception) {
            null
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    private fun findContainingFolder(uri: URI): URI? {
        for (w in workspaceFolders) {
            val folderUri = Uris.parseOrNull(w.uri) ?: continue
            if (folderUri.relativize(uri) != uri) return folderUri
        }
        return null
    }

    /**
     * Returns the ordered list of tier-3 workspace folder URIs to search when
     * [config.unqualifiedCrossProjectSearch] is enabled:
     * - Excludes the calling project's folder
     * - Applies [config.projectSearchExclude]
     * - Orders by [config.projectSearchOrder] (listed first), then alphabetical by folder name
     * - Only projects with ig.ini (from [LibraryResolutionManager.igProjects])
     */
    private fun tier3FolderUris(
        containingFolderUri: URI,
        config: LibraryResolutionConfig,
    ): List<URI> {
        val igProjects = namespaceManager.igProjects()
        log.debug(
            "tier3FolderUris: containingFolder={}, igProjects=[{}]",
            containingFolderUri,
            igProjects.joinToString { "${it.name}(${it.uri})" },
        )
        val result = igProjects
            .filter { Uris.parseOrNull(it.uri) != containingFolderUri }
            .filter { it.name !in config.projectSearchExclude }
            .sortedWith(
                compareBy(
                    { w ->
                        val idx = config.projectSearchOrder.indexOf(w.name)
                        if (idx < 0) Int.MAX_VALUE else idx
                    },
                    { it.name },
                ),
            )
            .mapNotNull { Uris.parseOrNull(it.uri) }
        log.debug("tier3FolderUris: searching {} project(s): {}", result.size, result)
        return result
    }

    /**
     * BFS within [root] looking for an exact version match.
     * Returns the first (shallowest) file whose name matches `{name}-{version}.cql`.
     * Also handles non-parseable version strings (e.g. "0.1.000-cibuild") via string comparison.
     * Returns null if [version] is null (no version to match exactly).
     */
    private fun bfsExact(
        root: File,
        name: String,
        version: String?,
    ): File? {
        if (version == null) return null
        if (!root.exists() || !root.isDirectory) return null
        val requestedVersion = tryParseVersion(version)
        var currentLevel = listOf(root)

        while (currentLevel.isNotEmpty()) {
            val nextLevel = mutableListOf<File>()
            for (dir in currentLevel) {
                for (file in (dir.listFiles() ?: emptyArray())) {
                    if (file.isDirectory) {
                        nextLevel.add(file)
                        continue
                    }
                    if (!file.isFile || !file.name.endsWith(".cql")) continue

                    // String-level check first: handles non-parseable versions like "0.1.000-cibuild"
                    if (file.nameWithoutExtension.equals("$name-$version", ignoreCase = true)) {
                        return file
                    }

                    // Semantic version check: parsed Version.compareTo == 0
                    if (requestedVersion != null) {
                        val (parsedName, parsedVersion) = getNameAndVersion(file.name)
                        if (parsedName.equals(name, ignoreCase = true) &&
                            parsedVersion != null &&
                            parsedVersion.compareTo(requestedVersion) == 0
                        ) {
                            return file
                        }
                    }
                }
            }
            currentLevel = nextLevel
        }
        return null
    }

    /**
     * BFS within [root] looking for a compatible or any-version match.
     * At each depth level, returns the newest compatible file among candidates at that level.
     * STRICT mode never returns a match from this method (all candidates are rejected).
     */
    private fun bfsCompatible(
        root: File,
        name: String,
        version: String?,
        mode: LibraryResolutionMode,
    ): File? {
        if (!root.exists() || !root.isDirectory) return null
        val requestedVersion = version?.let { tryParseVersion(it) }
        var currentLevel = listOf(root)

        while (currentLevel.isNotEmpty()) {
            val nextLevel = mutableListOf<File>()
            var bestFile: File? = null
            var bestVersion: Version? = null

            for (dir in currentLevel) {
                for (file in (dir.listFiles() ?: emptyArray())) {
                    if (file.isDirectory) {
                        nextLevel.add(file)
                        continue
                    }
                    if (!file.isFile || !file.name.endsWith(".cql")) continue
                    val (parsedName, parsedVersion) = getNameAndVersion(file.name)
                    if (!parsedName.equals(name, ignoreCase = true)) continue
                    if (!isCompatible(parsedVersion, requestedVersion, mode)) continue

                    // Keep newest at this depth level
                    if (isNewer(parsedVersion, bestVersion)) {
                        bestFile = file
                        bestVersion = parsedVersion
                    }
                }
            }

            if (bestFile != null) return bestFile
            currentLevel = nextLevel
        }
        return null
    }

    private fun isCompatible(
        found: Version?,
        requested: Version?,
        mode: LibraryResolutionMode,
    ): Boolean {
        if (requested == null) return true  // no version specified → any version acceptable
        if (found == null) return true      // unversioned file: no version to match against, accept in all modes (including STRICT)
        return when (mode) {
            LibraryResolutionMode.STRICT -> false
            LibraryResolutionMode.PATCH_FLEXIBLE ->
                found.majorVersion == requested.majorVersion &&
                    found.minorVersion == requested.minorVersion &&
                    (found.patchVersion ?: 0) >= (requested.patchVersion ?: 0)
        }
    }

    private fun isNewer(
        candidate: Version?,
        current: Version?,
    ): Boolean {
        if (current == null) return true          // any file beats nothing
        if (candidate == null) return false       // versioned beats unversioned
        return candidate.compareTo(current) > 0
    }

    private fun toFile(uri: URI): File? =
        try {
            Paths.get(uri).toFile()
        } catch (e: Exception) {
            log.warn("Cannot convert URI to file path: {}", uri)
            null
        }
}
