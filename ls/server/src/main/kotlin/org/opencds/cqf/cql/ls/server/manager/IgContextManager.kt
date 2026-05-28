package org.opencds.cqf.cql.ls.server.manager

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.fhir.npm.ILibraryReader
import org.cqframework.fhir.npm.NpmLibrarySourceProvider
import org.cqframework.fhir.npm.NpmModelInfoProvider
import org.cqframework.fhir.npm.NpmProcessor
import org.cqframework.fhir.utilities.IGContext
import org.cqframework.fhir.utilities.LoggerAdapter
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.hl7.cql.model.NamespaceInfo
import org.hl7.fhir.utilities.npm.FilesystemPackageCacheManager
import org.hl7.fhir.utilities.npm.NpmPackage
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream

open class IgContextManager(private val contentService: ContentService) {
    companion object {
        private val log = LoggerFactory.getLogger(IgContextManager::class.java)
        private val FHIR_CACHE_DIR = File(System.getProperty("user.home"), ".fhir/packages")
    }

    private val cachedContext = ConcurrentHashMap<URI, Optional<NpmProcessor>>()

    // Cached IGContext per workspace root — retained even when NpmProcessor fails so the
    // partial-npm fallback can read sourceIg.dependsOn without re-parsing ig.ini.
    private val cachedIgContext = ConcurrentHashMap<URI, Optional<IGContext>>()

    fun getContext(uri: URI): NpmProcessor? {
        val root = Uris.getHead(uri)
        return cachedContext.computeIfAbsent(root) { readContext(it) }.orElse(null)
    }

    protected fun clearContext(uri: URI) {
        val root = Uris.getHead(uri)
        cachedContext.remove(root)
        cachedIgContext.remove(root)
    }

    protected fun readContext(rootUri: URI): Optional<NpmProcessor> {
        val igContext = findIgContext(rootUri) ?: return Optional.empty()
        cachedIgContext[rootUri] = Optional.of(igContext)
        return try {
            val fspcm = FilesystemPackageCacheManager.Builder().build()
            prewarmLocalDependencies(igContext, fspcm)
            Optional.of(NpmProcessor(igContext))
        } catch (e: Exception) {
            // Truncate before the '{...}' build-server map that the underlying library appends —
            // it can be thousands of characters and adds no actionable information.
            val shortMsg = e.message?.substringBefore(" {")?.substringBefore(" (") ?: e.javaClass.simpleName
            log.warn(
                "Failed to initialize NpmProcessor for {}: {}. " +
                    "Declared dependencies not in ~/.fhir/packages/ will be skipped; cached packages will still be used.",
                rootUri,
                shortMsg,
            )
            Optional.empty()
        }
    }

    @Synchronized
    fun setupLibraryManager(
        uri: URI,
        libraryManager: LibraryManager,
    ) {
        val npmProcessor = getContext(uri)
        if (npmProcessor != null) {
            setupWithNpmProcessor(npmProcessor, libraryManager)
        } else {
            // NpmProcessor failed (a declared dependency is not in the local FHIR package cache).
            // Fall back: load only the declared dependencies that ARE cached and register those.
            val root = Uris.getHead(uri)
            val igContext = cachedIgContext[root]?.orElse(null) ?: return
            setupWithAvailablePackages(igContext, libraryManager)
        }
    }

    private fun setupWithNpmProcessor(
        npmProcessor: NpmProcessor,
        libraryManager: LibraryManager,
    ) {
        val namespaceManager = libraryManager.namespaceManager
        npmProcessor.igNamespace?.let { namespaceManager.ensureNamespaceRegistered(it) }
        val fhirVersion = npmProcessor.igContext?.fhirVersion ?: return
        val reader: ILibraryReader = org.cqframework.fhir.npm.LibraryLoader(fhirVersion)
        val adapter = LoggerAdapter(log)
        val npmList = npmProcessor.getPackageManager().npmList
        // NpmLibrarySourceProvider is intentionally NOT registered here — FederatedLibrarySourceProvider
        // (registered in CqlCompilationManager) handles library source lookup for the NPM tier.
        // Registering it here too would cause duplicate resolution and unpredictable ordering.
        libraryManager.modelManager.modelInfoLoader.registerModelInfoProvider(
            NpmModelInfoProvider(npmList, reader, adapter),
        )

        val keys = mutableSetOf<String>()
        val uris = mutableSetOf<String>()
        for (n in npmProcessor.namespaces) {
            if (!keys.contains(n.name) && !uris.contains(n.uri)) {
                libraryManager.namespaceManager.addNamespace(n)
                keys.add(n.name)
                uris.add(n.uri)
            }
        }
    }

    private fun setupWithAvailablePackages(
        igContext: IGContext,
        libraryManager: LibraryManager,
    ) {
        val deps = igContext.sourceIg?.dependsOn ?: return
        val availablePackages = mutableListOf<NpmPackage>()

        for (dep in deps) {
            val packageId = dep.packageId?.takeIf { it.isNotEmpty() } ?: continue
            val version = dep.version?.takeIf { it.isNotEmpty() } ?: continue
            // For "dev" dependencies, the pre-warm installed them as "current" in the cache.
            val cacheVersion = if (version == "dev") "current" else version
            val packageDir = File(FHIR_CACHE_DIR, "$packageId#$cacheVersion")
            if (!packageDir.exists()) {
                log.debug("Skipping dependency {} version {} — not in local FHIR package cache", packageId, version)
                continue
            }
            try {
                availablePackages.add(NpmPackage.fromFolder(packageDir.path))
                log.info("Partial npm setup: loaded {} #{} from local cache", packageId, version)
            } catch (e: Exception) {
                log.warn("Could not load package {} #{} from local cache: {}", packageId, version, e.message)
            }
        }

        if (availablePackages.isEmpty()) return

        val fhirVersion = igContext.fhirVersion
        val reader: ILibraryReader = org.cqframework.fhir.npm.LibraryLoader(fhirVersion)
        val adapter = LoggerAdapter(log)
        libraryManager.librarySourceLoader.registerProvider(
            NpmLibrarySourceProvider(availablePackages, reader, adapter),
        )
        libraryManager.modelManager.modelInfoLoader.registerModelInfoProvider(
            NpmModelInfoProvider(availablePackages, reader, adapter),
        )

        // Register the canonical URL of each loaded package as a namespace so
        // namespace-qualified includes (e.g. "hl7.fhir.uv.cql".FHIRHelpers) resolve correctly.
        for (pkg in availablePackages) {
            val name = pkg.name() ?: continue
            val canonical = pkg.canonical() ?: continue
            libraryManager.namespaceManager.ensureNamespaceRegistered(NamespaceInfo(name, canonical))
        }
    }

    protected open fun findIgContext(uri: URI): IGContext? {
        log.info("Searching for ini file in {}", uri)
        var current = uri
        while (true) {
            val parent = Uris.getHead(current)
            if (parent == current) break
            current = parent
            val igIniPath = Uris.addPath(parent, "/ig.ini") ?: continue
            log.info("Attempting to read ini from path {}", igIniPath)
            contentService.read(igIniPath)?.use {
                log.info("Initializing ig from ini...")
                val igContext = IGContext(LoggerAdapter(log))
                igContext.initializeFromIni(Paths.get(igIniPath).toString())
                log.info("IGContext Initialized.")
                return igContext
            }
        }
        return null
    }

    private fun prewarmLocalDependencies(
        igContext: IGContext,
        fspcm: FilesystemPackageCacheManager,
    ) {
        val ig = igContext.sourceIg ?: return
        val rootDir = igContext.rootDir ?: return
        val workspaceRoot = Paths.get(rootDir).parent ?: return

        for (dep in ig.dependsOn) {
            if (!dep.hasPackageId() || dep.version != "dev") continue
            val depPackageId = dep.packageId

            val localIgContext = findLocalProject(workspaceRoot, depPackageId) ?: continue
            val canonical = localIgContext.canonicalBase ?: continue
            val fhirVersion = localIgContext.fhirVersion ?: "4.0.1"

            log.info(
                "Pre-warming cache for local package {} (FHIR {}) from {}",
                depPackageId,
                fhirVersion,
                localIgContext.rootDir,
            )
            try {
                val tgz = buildMinimalPackageTgz(depPackageId, canonical, fhirVersion)
                fspcm.addPackageToCache(depPackageId, "current", tgz.inputStream(), "local workspace")
            } catch (e: Exception) {
                log.warn("Failed to pre-warm cache for {}: {}", depPackageId, e.message)
                // Non-fatal — NpmPackageManager will fall through to setupWithAvailablePackages
            }
        }
    }

    private fun findLocalProject(
        workspaceRoot: Path,
        packageId: String,
    ): IGContext? {
        val dirs =
            try {
                Files.list(workspaceRoot).filter { Files.isDirectory(it) }.toList()
            } catch (e: IOException) {
                return null
            }
        for (dir in dirs) {
            val igIniPath = dir.resolve("ig.ini")
            if (!igIniPath.toFile().exists()) continue
            try {
                val candidate = IGContext(LoggerAdapter(log))
                candidate.initializeFromIni(igIniPath.toString())
                if (candidate.packageId == packageId) return candidate
            } catch (e: Exception) {
                // malformed ig.ini in a sibling project — skip
            }
        }
        return null
    }

    private fun buildMinimalPackageTgz(
        packageId: String,
        canonical: String,
        fhirVersion: String,
    ): ByteArray {
        val packageJson =
            """
            {
              "name": "$packageId",
              "version": "current",
              "canonical": "$canonical",
              "fhirVersions": ["$fhirVersion"]
            }
            """.trimIndent().toByteArray(Charsets.UTF_8)

        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                val entry = TarArchiveEntry("package/package.json")
                entry.size = packageJson.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(packageJson)
                tar.closeArchiveEntry()
            }
        }
        return baos.toByteArray()
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onMessageEvent(event: DidChangeWatchedFilesEvent) {
        for (e in event.params().changes) {
            if (e.uri.endsWith("ig.ini")) {
                Uris.parseOrNull(e.uri)?.let { clearContext(it) }
            }
        }
    }
}
