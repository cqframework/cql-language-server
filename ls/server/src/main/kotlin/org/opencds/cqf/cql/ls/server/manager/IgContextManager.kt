package org.opencds.cqf.cql.ls.server.manager

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.fhir.npm.ILibraryReader
import org.cqframework.fhir.npm.NpmLibrarySourceProvider
import org.cqframework.fhir.npm.NpmModelInfoProvider
import org.cqframework.fhir.npm.NpmPackageManager
import org.cqframework.fhir.utilities.IGContext
import org.cqframework.fhir.utilities.LoggerAdapter
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.hl7.cql.model.NamespaceInfo
import org.hl7.fhir.utilities.npm.NpmPackage
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream

open class IgContextManager(private val contentService: ContentService) {
    companion object {
        private val log = LoggerFactory.getLogger(IgContextManager::class.java)
        private val FHIR_CACHE_DIR = File(System.getProperty("user.home"), ".fhir/packages")
        private val mapper = ObjectMapper()

        // Matches `library Name`, `library "Quoted Name"`, `library ns.Name version '1.0.0'`.
        private val LIBRARY_HEADER_REGEX =
            Regex(
                """\blibrary\s+(?:"([^"]+)"|([\p{L}_][\p{L}\p{N}_]*(?:\.[\p{L}_][\p{L}\p{N}_]*)*))""" +
                    """(?:\s+version\s+'([^']*)')?""",
            )
        private val BLOCK_COMMENT_REGEX = Regex("""(?s)/\*.*?\*/""")
        private val LINE_COMMENT_REGEX = Regex("""//[^\n]*""")
    }

    private val cachedContext = ConcurrentHashMap<URI, Optional<IgPackageContext>>()

    // Cached IGContext per workspace root — retained even when npm package loading fails so the
    // partial-npm fallback can read sourceIg.dependsOn without re-parsing ig.ini.
    private val cachedIgContext = ConcurrentHashMap<URI, Optional<IGContext>>()

    /** dev project root dir → (source stamp, in-memory package) built from that project. */
    private val devPackageCache = ConcurrentHashMap<String, DevPackage>()

    /** packageId → dev project root dir, so repeat lookups skip the sibling-directory scan. */
    private val devProjectDirHint = ConcurrentHashMap<String, String>()

    private data class DevPackage(val projectDir: String, val stamp: Long, val pkg: NpmPackage)

    fun getContext(uri: URI): IgPackageContext? {
        val root = Uris.getHead(uri)
        // A context built from dev-dependency sources goes stale when those sources change;
        // the stamp comparison is a cheap stat walk, so it runs on every lookup.
        cachedContext[root]?.orElse(null)?.let { ctx ->
            val stale =
                ctx.devStamps.any { (dir, stamp) -> computeSourceStamp(Paths.get(dir)) != stamp }
            if (stale) cachedContext.remove(root)
        }
        return cachedContext.computeIfAbsent(root) { readContext(it) }.orElse(null)
    }

    protected fun clearContext(uri: URI) {
        val root = Uris.getHead(uri)
        cachedContext.remove(root)
        cachedIgContext.remove(root)
    }

    fun clearAllContexts() {
        cachedContext.clear()
        cachedIgContext.clear()
        devPackageCache.clear()
        devProjectDirHint.clear()
    }

    protected fun readContext(rootUri: URI): Optional<IgPackageContext> {
        val igContext = findIgContext(rootUri) ?: return Optional.empty()
        cachedIgContext[rootUri] = Optional.of(igContext)
        val sourceIg = igContext.sourceIg ?: return Optional.empty()

        // Build in-memory packages for "dev" (sibling project) dependencies and seed them into
        // NpmPackageManager's npmList — the LS must never plant stub packages in
        // ~/.fhir/packages (they corrupt the cache the IG Publisher and other tools share).
        val devPackages = mutableListOf<NpmPackage>()
        val devStamps = mutableMapOf<String, Long>()
        for (dep in sourceIg.dependsOn) {
            if (!dep.hasPackageId() || dep.version != "dev") continue
            val dev = getOrBuildDevPackage(igContext, dep.packageId)
            if (dev == null) {
                log.warn("Dev dependency {} has no resolvable sibling project — its libraries will not resolve", dep.packageId)
                continue
            }
            devPackages.add(dev.pkg)
            devStamps[dev.projectDir] = dev.stamp
        }

        return try {
            // Give the loader a copy of the IG with dev entries removed: its dedup check
            // (hasPackage) only consults the first npmList element and falls back to a
            // by-URI load, so seeded packages alone don't stop it from hitting the cache
            // (or the network) for dev dependencies.
            val seedIg = sourceIg.copy()
            seedIg.dependsOn.removeIf { it.version == "dev" }
            val packageManager = NpmPackageManager(seedIg, null, devPackages.toMutableList())
            Optional.of(IgPackageContext(igContext, packageManager, devStamps))
        } catch (e: Exception) {
            // Truncate before the '{...}' build-server map that the underlying library appends —
            // it can be thousands of characters and adds no actionable information.
            val shortMsg = e.message?.substringBefore(" {")?.substringBefore(" (") ?: e.javaClass.simpleName
            log.warn(
                "Failed to load npm packages for {}: {}. " +
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
        val pkgContext = getContext(uri)
        if (pkgContext != null) {
            setupWithPackageContext(pkgContext, libraryManager)
        } else {
            // Package loading failed (a declared dependency could not be resolved).
            // Fall back: load only the dependencies that ARE available and register those.
            val root = Uris.getHead(uri)
            val igContext = cachedIgContext[root]?.orElse(null) ?: return
            setupWithAvailablePackages(igContext, libraryManager)
        }
    }

    private fun setupWithPackageContext(
        pkgContext: IgPackageContext,
        libraryManager: LibraryManager,
    ) {
        val namespaceManager = libraryManager.namespaceManager
        pkgContext.igNamespace?.let { namespaceManager.ensureNamespaceRegistered(it) }
        val fhirVersion = pkgContext.igContext.fhirVersion
        val reader: ILibraryReader = org.cqframework.fhir.npm.LibraryLoader(fhirVersion)
        val adapter = LoggerAdapter(log)
        val npmList = pkgContext.packageManager.npmList
        // NpmLibrarySourceProvider is intentionally NOT registered here — FederatedLibrarySourceProvider
        // (registered in CqlCompilationManager) handles library source lookup for the NPM tier.
        // Registering it here too would cause duplicate resolution and unpredictable ordering.
        libraryManager.modelManager.modelInfoLoader.registerModelInfoProvider(
            NpmModelInfoProvider(npmList, reader, adapter),
        )

        val keys = mutableSetOf<String>()
        val uris = mutableSetOf<String>()
        for (n in pkgContext.namespaces) {
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
            if (version == "dev") {
                val dev = getOrBuildDevPackage(igContext, packageId)
                if (dev != null) {
                    availablePackages.add(dev.pkg)
                    log.info("Partial npm setup: built in-memory dev package for {}", packageId)
                } else {
                    log.debug("Skipping dev dependency {} — no sibling project found", packageId)
                }
                continue
            }
            val packageDir = File(FHIR_CACHE_DIR, "$packageId#$version")
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

    private fun getOrBuildDevPackage(
        parentIgContext: IGContext,
        packageId: String,
    ): DevPackage? {
        val parentRoot = parentIgContext.rootDir ?: return null
        val workspaceRoot = Paths.get(parentRoot).parent ?: return null
        val depIgContext = resolveDevProject(workspaceRoot, packageId) ?: return null
        val projectDir = depIgContext.rootDir?.let(Paths::get) ?: return null

        val key = projectDir.toString()
        val stamp = computeSourceStamp(projectDir)
        devPackageCache[key]?.let { if (it.stamp == stamp) return it }

        return try {
            log.info("Building in-memory dev package for {} from {}", packageId, key)
            val pkg = NpmPackage.fromPackage(ByteArrayInputStream(buildDevPackageTgz(depIgContext)))
            DevPackage(key, stamp, pkg).also { devPackageCache[key] = it }
        } catch (e: Exception) {
            log.warn("Failed to build in-memory dev package for {}: {}", packageId, e.message)
            null
        }
    }

    private fun resolveDevProject(
        workspaceRoot: Path,
        packageId: String,
    ): IGContext? {
        devProjectDirHint[packageId]?.let { hintedDir ->
            val igIniPath = Paths.get(hintedDir).resolve("ig.ini")
            if (igIniPath.toFile().exists()) {
                try {
                    val candidate = IGContext(LoggerAdapter(log))
                    candidate.initializeFromIni(igIniPath.toString())
                    if (candidate.packageId == packageId) return candidate
                } catch (e: Exception) {
                    // hint went stale (project moved or ig.ini broke) — fall through to the scan
                }
            }
            devProjectDirHint.remove(packageId)
        }
        return findLocalProject(workspaceRoot, packageId)?.also { found ->
            found.rootDir?.let { devProjectDirHint[packageId] = it }
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

    /**
     * Max lastModified across the dev project's package-relevant sources. Directories are
     * included because a file deletion bumps only the containing directory's mtime.
     */
    internal fun computeSourceStamp(projectDir: Path): Long {
        var max = 0L
        fun visit(p: Path) {
            if (!Files.exists(p)) return
            Files.walk(p).use { stream ->
                stream.forEach { max = maxOf(max, it.toFile().lastModified()) }
            }
        }
        visit(projectDir.resolve("input/cql"))
        visit(projectDir.resolve("input/resources/library"))
        visit(projectDir.resolve("input/vocabulary"))
        for (f in listOf("ig.ini", "ig.json", "input/ig.json")) {
            max = maxOf(max, projectDir.resolve(f).toFile().lastModified())
        }
        return max
    }

    /**
     * Builds a complete in-memory npm package (tar+gzip) for a dev dependency project:
     * `package.json` from the IG metadata, `Library-*.json` generated from `input/cql` sources
     * (base64-embedded CQL), authored Library resources from `input/resources/library`, and
     * `input/vocabulary` resources flattened into `package/` (subfolders are invisible to
     * NpmPackage's canonical-URL index).
     */
    internal fun buildDevPackageTgz(depIgContext: IGContext): ByteArray {
        val packageId = requireNotNull(depIgContext.packageId) { "dev project has no packageId" }
        val canonical = requireNotNull(depIgContext.canonicalBase) { "dev project has no canonical base" }
        val rootDir = requireNotNull(depIgContext.rootDir) { "dev project has no root directory" }
        val fhirVersion = depIgContext.fhirVersion.ifBlank { "4.0.1" }
        val packageVersion = depIgContext.sourceIg?.version ?: "current"

        val entries = LinkedHashMap<String, ByteArray>()

        val packageJson = mapper.createObjectNode()
        packageJson.put("name", packageId)
        packageJson.put("version", packageVersion)
        packageJson.put("canonical", canonical)
        packageJson.putArray("fhirVersions").add(fhirVersion)
        entries["package/package.json"] = mapper.writeValueAsBytes(packageJson)

        // Generated Library resources win over authored ones with the same url+version.
        val generated = mutableSetOf<Pair<String, String?>>()

        val cqlDir = Paths.get(rootDir, "input", "cql")
        if (Files.exists(cqlDir)) {
            Files.walk(cqlDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".cql") }
                    .sorted()
                    .forEach { cqlPath ->
                        val cqlBytes = Files.readAllBytes(cqlPath)
                        val header = parseCqlHeader(String(cqlBytes, Charsets.UTF_8))
                        if (header == null) {
                            log.debug("Skipping {} — no library declaration found", cqlPath.fileName)
                            return@forEach
                        }
                        val (name, version) = header
                        val url = "$canonical/Library/$name"
                        entries.putLibraryEntry(name, version, buildLibraryJson(url, name, version, cqlBytes))
                        generated.add(url to version)
                    }
            }
        }

        copyResourceJsons(Paths.get(rootDir, "input", "resources", "library"), entries) { node ->
            if (node.path("resourceType").asText() != "Library") return@copyResourceJsons false
            val url = node.path("url").takeIf { it.isTextual }?.asText()
            val version = node.path("version").takeIf { it.isTextual }?.asText()
            url == null || !generated.contains(url to version)
        }
        copyResourceJsons(Paths.get(rootDir, "input", "vocabulary"), entries) { true }

        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                for ((entryName, bytes) in entries) {
                    val entry = TarArchiveEntry(entryName)
                    entry.size = bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        return baos.toByteArray()
    }

    private fun MutableMap<String, ByteArray>.putLibraryEntry(
        name: String,
        version: String?,
        bytes: ByteArray,
    ) {
        var entryName = "package/Library-$name.json"
        if (containsKey(entryName) && version != null) {
            entryName = "package/Library-$name-$version.json"
        }
        this[entryName] = bytes
    }

    // Field order is load-bearing: HAPI's NpmPackageIndexBuilder mis-tracks JSON nesting when an
    // object/array field precedes url/version, leaving the resource out of the package index —
    // scalar fields must come first.
    private fun buildLibraryJson(
        url: String,
        name: String,
        version: String?,
        cqlBytes: ByteArray,
    ): ByteArray {
        val lib = mapper.createObjectNode()
        lib.put("resourceType", "Library")
        lib.put("url", url)
        version?.let { lib.put("version", it) }
        lib.put("name", name)
        lib.put("status", "active")
        val coding = lib.putObject("type").putArray("coding").addObject()
        coding.put("system", "http://terminology.hl7.org/CodeSystem/library-type")
        coding.put("code", "logic-library")
        val content = lib.putArray("content").addObject()
        content.put("contentType", "text/cql")
        content.put("data", Base64.getEncoder().encodeToString(cqlBytes))
        return mapper.writeValueAsBytes(lib)
    }

    /**
     * Copies each `*.json` under [dir] (recursively) flat into `package/` when [include]
     * accepts it, re-serialized with scalar identity fields hoisted first (see
     * [buildLibraryJson] for why order matters). Existing entries win on filename collision.
     */
    private fun copyResourceJsons(
        dir: Path,
        entries: MutableMap<String, ByteArray>,
        include: (ObjectNode) -> Boolean,
    ) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
                .sorted()
                .forEach { src ->
                    val entryName = "package/${src.fileName}"
                    if (entries.containsKey(entryName)) {
                        log.warn("Skipping {} — filename collision in dev package", src.fileName)
                        return@forEach
                    }
                    try {
                        val node = mapper.readTree(Files.readAllBytes(src)) as? ObjectNode ?: return@forEach
                        if (!include(node)) return@forEach
                        entries[entryName] = mapper.writeValueAsBytes(hoistScalars(node))
                    } catch (e: Exception) {
                        log.warn("Skipping unparseable resource {}: {}", src, e.message)
                    }
                }
        }
    }

    private fun hoistScalars(node: ObjectNode): ObjectNode {
        val out = mapper.createObjectNode()
        for (field in listOf("resourceType", "id", "url", "version")) {
            val value = node.get(field)
            if (value != null && value.isValueNode) out.set<ObjectNode>(field, value)
        }
        for ((key, value) in node.fields()) {
            if (!out.has(key)) out.set<ObjectNode>(key, value)
        }
        return out
    }

    /**
     * Extracts (name, version) from the first `library` declaration, ignoring comments.
     * A namespace-qualified declaration (`library ns.Name`) declares library `Name`; the
     * npm provider's canonical URL is built from the unqualified name.
     */
    internal fun parseCqlHeader(cql: String): Pair<String, String?>? {
        val noComments =
            cql
                .replace(BLOCK_COMMENT_REGEX, " ")
                .replace(LINE_COMMENT_REGEX, " ")
        val match = LIBRARY_HEADER_REGEX.find(noComments) ?: return null
        val quoted = match.groupValues[1]
        val plain = match.groupValues[2]
        val name = if (quoted.isNotEmpty()) quoted else plain.substringAfterLast('.')
        val version = match.groupValues[3].takeIf { it.isNotEmpty() }
        return name to version
    }

    @Subscribe
    fun onMessageEvent(event: DidChangeWatchedFilesEvent) {
        for (e in event.params().changes) {
            val uriString = e.uri
            when {
                uriString.endsWith("ig.ini") -> {
                    Uris.parseOrNull(uriString)?.let { clearContext(it) }
                }
                uriString.endsWith("/input/ig.json") ||
                    (uriString.substringAfterLast('/').startsWith("ImplementationGuide-") &&
                        uriString.endsWith(".json")) -> {
                    clearAllContexts()
                }
            }
        }
    }
}
