package org.opencds.cqf.cql.ls.server.manager

import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.fhir.utilities.IGContext
import org.cqframework.fhir.utilities.LoggerAdapter
import org.eclipse.lsp4j.WorkspaceFolder
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.hl7.cql.model.NamespaceInfo
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.nio.file.Paths

open class LibraryResolutionManager(
    private val workspaceFolders: List<WorkspaceFolder>,
) {
    companion object {
        private val log = LoggerFactory.getLogger(LibraryResolutionManager::class.java)
    }

    // Namespace index: built lazily from ig.ini files; null means not yet built or invalidated
    @Volatile private var namespaceIndex: Map<String, NamespaceEntry>? = null

    private data class NamespaceEntry(
        val namespaceInfo: NamespaceInfo,
        val inputCqlUri: URI,
        val workspaceFolder: WorkspaceFolder,
    )

    /**
     * Returns workspace folders that have an ig.ini file, suitable for namespace-qualified
     * cross-project resolution and opt-in tier 3 unqualified search.
     */
    open fun igProjects(): List<WorkspaceFolder> =
        getOrBuildNamespaceIndex().values.map { it.workspaceFolder }

    /**
     * Resolves a canonical URL (from a namespace-qualified include's `identifier.system`) to
     * the `input/cql/` URI of the workspace project whose ig.ini declares that canonical URL.
     * Returns null if no matching project is found.
     */
    open fun resolveCanonicalUrl(canonicalUrl: String): URI? =
        getOrBuildNamespaceIndex()[canonicalUrl]?.inputCqlUri

    /**
     * Returns the `input/` directory path for each indexed workspace project.
     * Used by [org.opencds.cqf.cql.ls.server.repository.ig.standard.FederatedTerminologyRepo]
     * to search vocabulary across all workspace projects during Execute CQL.
     *
     * Derivation: each entry's [NamespaceEntry.inputCqlUri] points to `{project}/input/cql`.
     * One [Uris.getHead] call yields `{project}/input/`, which is the correct root for
     * [org.opencds.cqf.cql.ls.server.repository.ig.standard.IgStandardRepository] —
     * it will resolve `{project}/input/vocabulary/` automatically via convention detection.
     */
    fun getInputDirectories(): List<java.nio.file.Path> =
        getOrBuildNamespaceIndex().values.mapNotNull { entry ->
            try {
                Paths.get(Uris.getHead(entry.inputCqlUri))
            } catch (e: Exception) {
                log.warn("Could not derive input directory from {}: {}", entry.inputCqlUri, e.message)
                null
            }
        }

    /**
     * Registers the package-ID namespaces of all workspace projects that have ig.ini into the
     * given library manager. Must be called AFTER [IgContextManager.setupLibraryManager] so that
     * [org.hl7.cql.model.NamespaceManager.ensureNamespaceRegistered] is a safe no-op for any
     * namespace that npm already registered.
     */
    fun registerWorkspaceNamespaces(libraryManager: LibraryManager) {
        val namespaceManager = libraryManager.namespaceManager
        for ((_, entry) in getOrBuildNamespaceIndex()) {
            try {
                namespaceManager.ensureNamespaceRegistered(entry.namespaceInfo)
            } catch (e: IllegalStateException) {
                // Two workspace projects share the same canonical base URL but have different
                // package IDs (e.g. both use https://example.org).  NamespaceManager.addNamespace
                // throws when a URI is already registered under a different name.  Log and skip —
                // the first registration wins; the second is a duplicate-URI project and its
                // namespace-qualified includes will not resolve from this compilation context.
                log.warn(
                    "Skipping namespace '{}' (uri='{}') — URI already registered under a different name: {}",
                    entry.namespaceInfo.name,
                    entry.namespaceInfo.uri,
                    e.message,
                )
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onMessageEvent(event: DidChangeWatchedFilesEvent) {
        for (e in event.params().changes) {
            if (e.uri.endsWith("ig.ini")) {
                namespaceIndex = null
            }
        }
    }

    private fun getOrBuildNamespaceIndex(): Map<String, NamespaceEntry> {
        return namespaceIndex ?: synchronized(this) {
            namespaceIndex ?: buildNamespaceIndex().also { namespaceIndex = it }
        }
    }

    /**
     * Reads the package ID and canonical base URL from an ig.ini file.
     * Returns null when either field is absent; throws on I/O or parse failure
     * (caller catches and skips the folder).
     * Declared protected open for test overriding — see [IgContextManager.findIgContext].
     */
    protected open fun readIgContextInfo(igIniFile: File): Pair<String, String>? {
        val igContext = IGContext(LoggerAdapter(log))
        igContext.initializeFromIni(igIniFile.path)
        val packageId = igContext.packageId ?: return null
        val canonicalBase = igContext.canonicalBase ?: return null
        return packageId to canonicalBase
    }

    private fun buildNamespaceIndex(): Map<String, NamespaceEntry> {
        val result = mutableMapOf<String, NamespaceEntry>()
        log.debug("buildNamespaceIndex: scanning {} workspace folder(s)", workspaceFolders.size)
        for (w in workspaceFolders) {
            val folderUri = Uris.parseOrNull(w.uri) ?: continue
            val folderFile =
                try {
                    Paths.get(folderUri).toFile()
                } catch (e: Exception) {
                    continue
                }
            log.debug("buildNamespaceIndex: folder '{}' → {}", w.name, folderFile)

            // Check both the workspace folder root and one level of subdirectories.
            // Multi-project workspaces (e.g. PAS-sample-structure-r4/{Common,Policy2,...})
            // keep each IG project in a subdirectory; the workspace folder itself has no ig.ini.
            val dirsToScan =
                buildList {
                    add(folderFile)
                    folderFile.listFiles()?.filter { it.isDirectory }?.let { addAll(it) }
                }

            for (dir in dirsToScan) {
                val igIniFile = File(dir, "ig.ini")
                if (!igIniFile.exists()) continue
                // For a subdirectory project, synthesize a WorkspaceFolder so igProjects() and
                // tier-3 search resolve the correct input/cql path and folder name.
                val effectiveFolder =
                    if (dir == folderFile) {
                        w
                    } else {
                        WorkspaceFolder(dir.toURI().toString(), dir.name)
                    }
                val dirUri = dir.toURI()
                try {
                    // igNamespace is just NamespaceInfo(packageId, canonicalBase) — read directly
                    // from IGContext so we never need NpmProcessor here.  NpmProcessor loads all
                    // declared npm dependencies from the local FHIR package cache; if any are
                    // missing it throws, which would silently drop this project from the index and
                    // break namespace-qualified includes that point to local workspace libraries.
                    val (packageId, canonicalBase) = readIgContextInfo(igIniFile) ?: continue
                    val nsInfo = NamespaceInfo(packageId, canonicalBase)
                    val inputCqlUri =
                        Uris.addPath(dirUri, "input")
                            ?.let { Uris.addPath(it, "cql") }
                            ?: continue
                    log.debug(
                        "buildNamespaceIndex: indexed '{}' (canonical='{}', inputCql={})",
                        packageId,
                        canonicalBase,
                        inputCqlUri,
                    )
                    result[nsInfo.uri] = NamespaceEntry(nsInfo, inputCqlUri, effectiveFolder)
                } catch (e: Exception) {
                    log.warn("Failed to read ig context from {}: {}", igIniFile.path, e.message)
                }
            }
        }
        log.debug("buildNamespaceIndex: total {} namespace(s) indexed", result.size)
        return result
    }
}
