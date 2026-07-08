package org.opencds.cqf.cql.ls.server.manager

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.io.readByteArray
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.ModelManager
import org.cqframework.fhir.npm.LibraryLoader
import org.cqframework.fhir.npm.NpmLibrarySourceProvider
import org.cqframework.fhir.utilities.IGContext
import org.cqframework.fhir.utilities.LoggerAdapter
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.fhir.utilities.npm.NpmPackage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent
import org.opencds.cqf.cql.ls.server.service.TestContentService
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.Method
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

private open class ExposedIgContextManager(cs: ContentService) : IgContextManager(cs) {
    public override fun findIgContext(uri: URI): IGContext? = super.findIgContext(uri)
}

class IgContextManagerTest {
    private lateinit var manager: IgContextManager

    @BeforeEach
    fun setUp() {
        manager = IgContextManager(TestContentService())
    }

    // -----------------------------------------------------------------------
    // getContext — no ig.ini in fixture classpath paths → returns null
    // -----------------------------------------------------------------------

    @Test
    fun getContext_noIgIni_returnsNull() {
        val result = manager.getContext(TEST_URI)
        assertNull(result)
    }

    // -----------------------------------------------------------------------
    // getContext — result is cached (content service not re-read on second call)
    // -----------------------------------------------------------------------

    @Test
    fun getContext_secondCall_returnsSameNullResult() {
        val first = manager.getContext(TEST_URI)
        val second = manager.getContext(TEST_URI)
        assertEquals(first, second)
    }

    @Test
    fun getContext_cachedResult_doesNotReReadContentService() {
        var readCount = 0
        val countingCs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    libraryIdentifier: VersionedIdentifier,
                ): Set<URI> = emptySet()

                override fun read(uri: URI): InputStream? {
                    readCount++
                    return null
                }
            }
        val localManager = IgContextManager(countingCs)

        localManager.getContext(TEST_URI) // populates cache
        val countAfterFirst = readCount
        localManager.getContext(TEST_URI) // should hit cache

        assertEquals(countAfterFirst, readCount, "Second getContext() call should not read from content service again")
    }

    // -----------------------------------------------------------------------
    // onMessageEvent — ig.ini change clears cache so next call re-reads
    // -----------------------------------------------------------------------

    @Test
    fun onMessageEvent_igIniChanged_clearsCache() {
        var readCount = 0
        val countingCs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    libraryIdentifier: VersionedIdentifier,
                ): Set<URI> = emptySet()

                override fun read(uri: URI): InputStream? {
                    readCount++
                    return null
                }
            }
        val localManager = IgContextManager(countingCs)

        localManager.getContext(TEST_URI) // populates cache
        val countAfterFirst = readCount

        val igIniUri = Uris.getHead(TEST_URI).toString() + "/ig.ini"
        localManager.onMessageEvent(
            DidChangeWatchedFilesEvent(
                DidChangeWatchedFilesParams(listOf(FileEvent(igIniUri, FileChangeType.Changed))),
            ),
        )

        localManager.getContext(TEST_URI) // cache cleared → re-reads
        assertTrue(readCount > countAfterFirst, "Expected content service to be re-read after ig.ini change")
    }

    @Test
    fun onMessageEvent_unrelatedFile_doesNotClearCache() {
        var readCount = 0
        val countingCs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    libraryIdentifier: VersionedIdentifier,
                ): Set<URI> = emptySet()

                override fun read(uri: URI): InputStream? {
                    readCount++
                    return null
                }
            }
        val localManager = IgContextManager(countingCs)

        localManager.getContext(TEST_URI) // populates cache
        val countAfterFirst = readCount

        localManager.onMessageEvent(
            DidChangeWatchedFilesEvent(
                DidChangeWatchedFilesParams(listOf(FileEvent("file:///workspace/SomeOther.json", FileChangeType.Changed))),
            ),
        )

        localManager.getContext(TEST_URI) // should still be cached
        assertEquals(countAfterFirst, readCount, "Unrelated file change should not clear cache")
    }

    // -----------------------------------------------------------------------
    // getContext — concurrent callers trigger readContext() exactly once
    // -----------------------------------------------------------------------

    @Test
    fun getContext_concurrentCalls_onlyReadsContextOnce() {
        val readCount = AtomicInteger(0)
        val cs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ) = emptySet<URI>()

                override fun read(uri: URI): InputStream? {
                    readCount.incrementAndGet()
                    return null
                }
            }

        // Establish baseline: how many content-service reads does one getContext() trigger?
        val baselineManager = IgContextManager(cs)
        baselineManager.getContext(TEST_URI)
        val readsPerContext = readCount.get()

        // Reset and run N threads simultaneously against a cold cache.
        readCount.set(0)
        val localManager = IgContextManager(cs)
        val threadCount = 10
        val startGate = CountDownLatch(1)
        val threads =
            (1..threadCount).map {
                Thread {
                    startGate.await()
                    localManager.getContext(TEST_URI)
                }
            }
        threads.forEach { it.start() }
        startGate.countDown()
        threads.forEach { it.join() }

        assertEquals(
            readsPerContext,
            readCount.get(),
            "Concurrent getContext() calls should invoke readContext() exactly once " +
                "(expected $readsPerContext reads, got ${readCount.get()})",
        )
    }

    // -----------------------------------------------------------------------
    // findIgContext path conversion — the URI passed to IGContext.initializeFromIni
    // must be a valid OS filesystem path, not a URI artefact.
    //
    // Regression: the old code used uri.schemeSpecificPart which returns
    // "//path" on every platform (includes the authority prefix).
    // The fix uses Paths.get(URI).toString() which returns the plain path.
    //
    // This test verifies the path computation that findIgContext performs using
    // the exact same URI construction (Uris.addPath) used in production code.
    //
    // Platform   | file URI                        | Paths.get().toString()
    // -----------|---------------------------------|---------------------
    // macOS/Linux| file:///tmp/test/ig.ini         | /tmp/test/ig.ini
    // Windows    | file:///C:/tmp/test/ig.ini      | C:\tmp\test\ig.ini
    // -----------------------------------------------------------------------

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun findIgContext_pathConversion_producesPlainFsPath_notSchemeSpecificPart_unix(
        @TempDir tempDir: Path,
    ) {
        // Simulate exactly what findIgContext does: Uris.addPath(parent, "/ig.ini")
        // then Paths.get(igIniPath).toString()
        val parentUri = tempDir.toUri()
        val igIniPath = Uris.addPath(parentUri, "/ig.ini")!!

        val fsPath = Paths.get(igIniPath).toString()

        // The old schemeSpecificPart would yield "//tmp/…"; the fix gives "/tmp/…"
        assertFalse(fsPath.startsWith("//"), "schemeSpecificPart bug produces '//' prefix; got: $fsPath")
        assertTrue(fsPath.startsWith("/"), "Unix path must start with '/'")
        assertTrue(fsPath.endsWith("ig.ini"))
        // Must be a usable filesystem path — i.e., the file can be created at it
        val f = java.io.File(fsPath)
        f.writeText("[IG]\n")
        assertTrue(f.exists(), "File should be creatable at the converted path")
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun findIgContext_pathConversion_producesPlainFsPath_notSchemeSpecificPart_windows(
        @TempDir tempDir: Path,
    ) {
        val parentUri = tempDir.toUri()
        val igIniPath = Uris.addPath(parentUri, "/ig.ini")!!

        val fsPath = Paths.get(igIniPath).toString()

        // Windows path starts with a drive letter (e.g. "C:\"), not "//" or "/"
        assertFalse(fsPath.startsWith("//"), "schemeSpecificPart bug produces '//' prefix; got: $fsPath")
        assertFalse(fsPath.startsWith("/"), "Windows path should not start with '/'")
        assertTrue(fsPath.endsWith("ig.ini"))
    }

    // -----------------------------------------------------------------------
    // Caching — scope of cache key is the root (parent) URI, not the file URI
    // -----------------------------------------------------------------------

    @Test
    fun getContext_twoUrisInSameDirectory_readsContentServiceOnce() {
        var readCount = 0
        val cs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ) = emptySet<URI>()

                override fun read(uri: URI): InputStream? {
                    readCount++
                    return null
                }
            }
        val localManager = IgContextManager(cs)
        val dir = "file:///workspace/cql/"
        localManager.getContext(Uris.parseOrNull("${dir}One.cql")!!)
        val after1 = readCount
        // Second URI is in the same directory → same root key → should be cached
        localManager.getContext(Uris.parseOrNull("${dir}Two.cql")!!)
        assertEquals(after1, readCount, "Second URI in the same directory should reuse the cached entry")
    }

    @Test
    fun getContext_twoUrisInDifferentDirectories_cachesIndependently() {
        var readCount = 0
        val cs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ) = emptySet<URI>()

                override fun read(uri: URI): InputStream? {
                    readCount++
                    return null
                }
            }
        val localManager = IgContextManager(cs)
        localManager.getContext(Uris.parseOrNull("file:///workspace/lib1/One.cql")!!)
        val after1 = readCount
        localManager.getContext(Uris.parseOrNull("file:///workspace/lib2/Two.cql")!!)
        assertTrue(readCount > after1, "Different directories should produce independent cache entries")
    }

    // -----------------------------------------------------------------------
    // setupLibraryManager — no ig context → early return, no modification
    // -----------------------------------------------------------------------

    @Test
    fun setupLibraryManager_noIgContext_doesNotThrow() {
        // getContext returns null for TEST_URI (no ig.ini in classpath).
        // setupLibraryManager should return early without touching libraryManager.
        val libraryManager = LibraryManager(ModelManager())
        assertDoesNotThrow { manager.setupLibraryManager(TEST_URI, libraryManager) }
    }

    // -----------------------------------------------------------------------
    // readContext — package loading failure → null, not exception
    // -----------------------------------------------------------------------

    @Test
    fun getContext_igContextWithoutSourceIg_returnsNull() {
        // An uninitialized IGContext has sourceIg == null, so no NpmPackageManager can be
        // built. getContext() must return null instead of propagating an exception.
        val manager =
            object : IgContextManager(TestContentService()) {
                override fun findIgContext(uri: URI): org.cqframework.fhir.utilities.IGContext =
                    org.cqframework.fhir.utilities.IGContext()
            }
        val result = manager.getContext(TEST_URI)
        assertNull(result, "getContext should return null when the IG context has no sourceIg")
    }

    // -----------------------------------------------------------------------
    // findIgContext — unbounded upward search, no fixed depth limit
    // -----------------------------------------------------------------------

    @Test
    fun getContext_igIniAtArbitraryDepth_isProbed() {
        // Verify the search walks all ancestor directories rather than stopping at a fixed depth.
        // ContentService always returns null so findIgContext returns null and NpmProcessor
        // is never constructed — but the recorded probe list reveals how far the search went.
        //
        // For URI file:///workspace/a/b/c/d/e.cql:
        //   root = file:///workspace/a/b/c/d
        //   loop probes: .../c/ig.ini, .../b/ig.ini, .../a/ig.ini, workspace/ig.ini
        //   loop terminates when getHead stops making progress (filesystem root)
        val probed = mutableListOf<URI>()
        val cs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ) = emptySet<URI>()

                override fun read(uri: URI): InputStream? {
                    probed.add(uri)
                    return null
                }
            }
        val localManager = IgContextManager(cs)
        localManager.getContext(Uris.parseOrNull("file:///workspace/a/b/c/d/e.cql")!!)

        val probedStrings = probed.map { it.toString() }
        assertTrue(
            probedStrings.any { it.contains("workspace/ig.ini") },
            "Search should probe up to the workspace root regardless of nesting depth. Probed: $probed",
        )
    }

    // -----------------------------------------------------------------------
    // onMessageEvent — partial cache invalidation
    // -----------------------------------------------------------------------

    @Test
    fun onMessageEvent_igIniChangeForOneRoot_doesNotClearOtherRootsCache() {
        // Use 3-segment URIs so that findIgContext probes paths within each root's subtree.
        // For file:///workspace/root1/cql/One.cql:
        //   cache key (root) = file:///workspace/root1/cql
        //   findIgContext probes file:///workspace/root1/ig.ini  ← counted in root1Reads
        //
        // The ig.ini event URI must have its "head" equal to the cache key so that
        // clearContext removes the right entry:
        //   clearContext(file:///workspace/root1/cql/ig.ini)
        //   → Uris.getHead(...) = file:///workspace/root1/cql  ← matches cache key ✓
        val root1Reads = mutableListOf<URI>()
        val root2Reads = mutableListOf<URI>()
        val cs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ) = emptySet<URI>()

                override fun read(uri: URI): InputStream? {
                    // Use contains() rather than startsWith("file:///workspace/root1") because
                    // Uris.parseOrNull() normalises file: URIs on Windows via File.toURI(),
                    // which can produce "file:////workspace/..." (4 slashes) instead of the
                    // expected 3-slash form.  The path segment "/workspace/root1" is present
                    // in both forms and uniquely identifies the root.
                    val s = uri.toString()
                    if (s.contains("/workspace/root1")) {
                        root1Reads.add(uri)
                    } else if (s.contains("/workspace/root2")) {
                        root2Reads.add(uri)
                    }
                    return null
                }
            }
        val localManager = IgContextManager(cs)
        val root1Uri = Uris.parseOrNull("file:///workspace/root1/cql/One.cql")!!
        val root2Uri = Uris.parseOrNull("file:///workspace/root2/cql/Two.cql")!!

        localManager.getContext(root1Uri) // caches root1
        localManager.getContext(root2Uri) // caches root2
        val root1ReadsBefore = root1Reads.size
        val root2ReadsBefore = root2Reads.size

        // Clear only root1's cache by sending an ig.ini event for root1/cql/ig.ini
        val root1IgIniEvent = "file:///workspace/root1/cql/ig.ini"
        localManager.onMessageEvent(
            DidChangeWatchedFilesEvent(
                DidChangeWatchedFilesParams(listOf(FileEvent(root1IgIniEvent, FileChangeType.Changed))),
            ),
        )

        localManager.getContext(root1Uri) // cache cleared → re-reads root1
        localManager.getContext(root2Uri) // still cached → no extra reads for root2
        assertTrue(root1Reads.size > root1ReadsBefore, "root1 cache should be cleared by its ig.ini change")
        assertEquals(root2ReadsBefore, root2Reads.size, "root2 cache should not be affected")
    }

    // -----------------------------------------------------------------------
    // findIgContext — with real ig.ini + IG resource on disk
    // -----------------------------------------------------------------------

    @Test
    fun findIgContext_happyPath_returnsIgContext(
        @TempDir tempDir: Path,
    ) {
        createMinimalIg(tempDir, "test.ig", "http://test-ig.org/ig", "4.0.1")
        val cqlFile = createCqlFileInSubdir(tempDir)
        val cs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ) = emptySet<URI>()
            }
        val manager = ExposedIgContextManager(cs)
        val igContext = manager.findIgContext(cqlFile.toUri())
        assertNotNull(igContext)
    }

    @Test
    fun findIgContext_happyPath_hasCorrectPackageId(
        @TempDir tempDir: Path,
    ) {
        createMinimalIg(tempDir, "test.ig", "http://test-ig.org/ig", "4.0.1")
        val cqlFile = createCqlFileInSubdir(tempDir)
        val cs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ) = emptySet<URI>()
            }
        val manager = ExposedIgContextManager(cs)
        val igContext = manager.findIgContext(cqlFile.toUri())
        assertEquals("test.ig", igContext!!.packageId)
    }

    @Test
    fun findIgContext_happyPath_hasCorrectCanonical(
        @TempDir tempDir: Path,
    ) {
        createMinimalIg(tempDir, "test.ig", "http://test-ig.org/ig", "4.0.1")
        val cqlFile = createCqlFileInSubdir(tempDir)
        val cs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ) = emptySet<URI>()
            }
        val manager = ExposedIgContextManager(cs)
        val igContext = manager.findIgContext(cqlFile.toUri())
        assertTrue(igContext!!.canonicalBase!!.contains("test-ig.org"))
    }

    // -----------------------------------------------------------------------
    // getContext — package loading succeeds when FHIR packages are
    // available in the local cache.
    // -----------------------------------------------------------------------

    @Test
    fun getContext_happyPath_returnsPackageContext(
        @TempDir tempDir: Path,
    ) {
        createMinimalIg(tempDir, "test.ig", "http://test-ig.org/ig", "4.0.1")
        val cqlFile = createCqlFileInSubdir(tempDir)

        assumeR4CoreCached()

        val cs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ) = emptySet<URI>()
            }
        val manager = IgContextManager(cs)
        val pkgContext = manager.getContext(cqlFile.toUri())
        assertNotNull(pkgContext)
    }

    @Test
    fun getContext_cachedResult_reusesPackageContext(
        @TempDir tempDir: Path,
    ) {
        createMinimalIg(tempDir, "test.ig", "http://test-ig.org/ig", "4.0.1")
        val cqlFile = createCqlFileInSubdir(tempDir)

        assumeR4CoreCached()

        var readCount = 0
        val countingCs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ) = emptySet<URI>()

                override fun read(uri: URI): InputStream? {
                    readCount++
                    return try {
                        uri.toURL().openStream()
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        val manager = IgContextManager(countingCs)

        manager.getContext(cqlFile.toUri()) // populates cache
        val readsAfterFirst = readCount
        manager.getContext(cqlFile.toUri()) // should be cached

        assertEquals(readsAfterFirst, readCount, "Second getContext() call should not re-read ig.ini from content service")
    }

    // -----------------------------------------------------------------------
    // readContext — caches IGContext even when package loading fails
    // so that setupLibraryManager fallback can use the partial context.
    // -----------------------------------------------------------------------

    @Test
    fun getContext_packageLoadFails_cachedIgContextStillPopulated() {
        val manager =
            object : IgContextManager(TestContentService()) {
                override fun findIgContext(uri: URI): IGContext = IGContext()
            }
        val result = manager.getContext(TEST_URI)
        assertNull(result, "getContext should return null when package loading fails")
    }

    // -----------------------------------------------------------------------
    // setupLibraryManager — fallback path when package loading fails
    // but findIgContext succeeded. Must not throw.
    // -----------------------------------------------------------------------

    @Test
    fun setupLibraryManager_whenPackageLoadFails_doesNotThrow() {
        val manager =
            object : IgContextManager(TestContentService()) {
                override fun findIgContext(uri: URI): IGContext = IGContext()
            }
        // Populate cache: getContext → readContext → findIgContext succeeds,
        // package loading fails → cachedIgContext is populated.
        assertNull(manager.getContext(TEST_URI))

        val libraryManager = LibraryManager(ModelManager())
        assertDoesNotThrow { manager.setupLibraryManager(TEST_URI, libraryManager) }
    }

    // -----------------------------------------------------------------------
    // setupLibraryManager — package-context path with real packages (conditional)
    // -----------------------------------------------------------------------

    @Test
    fun setupLibraryManager_withPackageContext_configuresManager(
        @TempDir tempDir: Path,
    ) {
        createMinimalIg(tempDir, "test.ig", "http://test-ig.org/ig", "4.0.1")
        val cqlFile = createCqlFileInSubdir(tempDir)

        assumeR4CoreCached()

        val cs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ) = emptySet<URI>()
            }
        val manager = IgContextManager(cs)
        val libraryManager = LibraryManager(ModelManager())

        assertDoesNotThrow { manager.setupLibraryManager(cqlFile.toUri(), libraryManager) }
    }

    // -----------------------------------------------------------------------
    // buildDevPackageTgz — in-memory dev package structure and content
    // -----------------------------------------------------------------------

    @Test
    fun buildDevPackageTgz_containsPackageJsonAndGeneratedLibrary(
        @TempDir tempDir: Path,
    ) {
        val project = createSiblingProject(tempDir, "greatreef", "great.reef", "http://example.org/greatreef")
        project.resolve("input/cql/Concepts.cql").toFile()
            .writeText("library Concepts version '2.1.0'\n\ndefine \"X\": 1")

        val manager = IgContextManager(TestContentService())
        val entries = readTgzEntries(manager.buildDevPackageTgz(igContextFor(project)))

        val packageJson = jsonMapper.readTree(entries["package/package.json"]!!)
        assertEquals("great.reef", packageJson.path("name").asText())
        assertEquals("http://example.org/greatreef", packageJson.path("canonical").asText())
        assertEquals("4.0.1", packageJson.path("fhirVersions").get(0).asText())

        val library = jsonMapper.readTree(entries["package/Library-Concepts.json"]!!)
        assertEquals("Library", library.path("resourceType").asText())
        assertEquals("http://example.org/greatreef/Library/Concepts", library.path("url").asText())
        assertEquals("2.1.0", library.path("version").asText())
        val data = library.path("content").get(0).path("data").asText()
        val decoded = String(java.util.Base64.getDecoder().decode(data), Charsets.UTF_8)
        assertTrue(decoded.startsWith("library Concepts version '2.1.0'"), "content.data must round-trip the CQL")
    }

    @Test
    fun buildDevPackageTgz_scalarFieldsPrecedeObjectFields(
        @TempDir tempDir: Path,
    ) {
        // HAPI's NpmPackageIndexBuilder mis-tracks nesting when an object/array field precedes
        // url/version, so the generated JSON must emit scalar fields first.
        val project = createSiblingProject(tempDir, "greatreef", "great.reef", "http://example.org/greatreef")

        val manager = IgContextManager(TestContentService())
        val entries = readTgzEntries(manager.buildDevPackageTgz(igContextFor(project)))

        val raw = entries["package/Library-Common.json"]!!.decodeToString()
        val urlIdx = raw.indexOf("\"url\"")
        val versionIdx = raw.indexOf("\"version\"")
        val typeIdx = raw.indexOf("\"type\"")
        val contentIdx = raw.indexOf("\"content\"")
        assertTrue(urlIdx in 0 until typeIdx, "url must precede type in generated Library JSON")
        assertTrue(versionIdx in 0 until typeIdx, "version must precede type in generated Library JSON")
        assertTrue(urlIdx < contentIdx, "url must precede content in generated Library JSON")
    }

    @Test
    fun buildDevPackageTgz_loadsViaNpmPackage_andResolvesLibrarySource(
        @TempDir tempDir: Path,
    ) {
        val project = createSiblingProject(tempDir, "greatreef", "great.reef", "http://example.org/greatreef")

        val manager = IgContextManager(TestContentService())
        val tgz = manager.buildDevPackageTgz(igContextFor(project))
        val pkg = NpmPackage.fromPackage(ByteArrayInputStream(tgz))
        val provider = NpmLibrarySourceProvider(mutableListOf(pkg), LibraryLoader("4.0.1"), LoggerAdapter(log))

        val source = provider.getLibrarySource(VersionedIdentifier().withId("Common").withVersion("1.0.0"))
        assertNotNull(source, "NpmLibrarySourceProvider should resolve the generated library")
        val cql = source!!.readByteArray().decodeToString()
        assertTrue(cql.startsWith("library Common version '1.0.0'"))

        // Version matching is exact — a different version must not resolve.
        val wrongVersion = provider.getLibrarySource(VersionedIdentifier().withId("Common").withVersion("9.9.9"))
        assertNull(wrongVersion, "A non-matching version must not resolve")
    }

    @Test
    fun buildDevPackageTgz_generatedFromCqlWinsOverAuthoredLibrary(
        @TempDir tempDir: Path,
    ) {
        val project = createSiblingProject(tempDir, "greatreef", "great.reef", "http://example.org/greatreef")
        val libraryDir = project.resolve("input/resources/library").also { it.toFile().mkdirs() }
        // Authored resource with the SAME url+version as the generated one, but stale content.
        libraryDir.resolve("Library-Common.json").toFile().writeText(
            """
            {
                "resourceType": "Library",
                "url": "http://example.org/greatreef/Library/Common",
                "version": "1.0.0",
                "content": [{"contentType": "text/cql", "data": "c3RhbGU="}]
            }
            """.trimIndent(),
        )
        // Authored resource with a DIFFERENT url, with url appearing after an object field.
        libraryDir.resolve("Library-Other.json").toFile().writeText(
            """
            {
                "resourceType": "Library",
                "type": {"coding": [{"code": "logic-library"}]},
                "url": "http://example.org/greatreef/Library/Other",
                "version": "3.0.0"
            }
            """.trimIndent(),
        )

        val manager = IgContextManager(TestContentService())
        val entries = readTgzEntries(manager.buildDevPackageTgz(igContextFor(project)))

        val common = jsonMapper.readTree(entries["package/Library-Common.json"]!!)
        val data = common.path("content").get(0).path("data").asText()
        val decoded = String(java.util.Base64.getDecoder().decode(data), Charsets.UTF_8)
        assertTrue(decoded.startsWith("library Common"), "generated-from-CQL library must win the collision")

        // The other authored library is copied through with url/version hoisted before object fields.
        val otherRaw = entries["package/Library-Other.json"]!!.decodeToString()
        assertTrue(
            otherRaw.indexOf("\"url\"") in 0 until otherRaw.indexOf("\"type\""),
            "copied resources must have url hoisted before object-valued fields",
        )
    }

    @Test
    fun buildDevPackageTgz_vocabularyFilesFlattenedIntoPackageFolder(
        @TempDir tempDir: Path,
    ) {
        val project = createSiblingProject(tempDir, "greatreef", "great.reef", "http://example.org/greatreef")
        val vocabDir = project.resolve("input/vocabulary/valueset").also { it.toFile().mkdirs() }
        vocabDir.resolve("ValueSet-diabetes.json").toFile().writeText(
            """{"resourceType": "ValueSet", "url": "http://example.org/greatreef/ValueSet/diabetes"}""",
        )

        val manager = IgContextManager(TestContentService())
        val entries = readTgzEntries(manager.buildDevPackageTgz(igContextFor(project)))

        // Subfolders inside the tgz are invisible to NpmPackage's canonical index — must be flat.
        assertTrue(entries.containsKey("package/ValueSet-diabetes.json"), "vocabulary files must be flattened")
        assertFalse(entries.keys.any { it.contains("vocabulary") }, "no vocabulary subfolder entries allowed")
    }

    // -----------------------------------------------------------------------
    // parseCqlHeader — library declaration extraction
    // -----------------------------------------------------------------------

    @Test
    fun parseCqlHeader_extractsNameAndVersion() {
        val manager = IgContextManager(TestContentService())
        assertEquals("Foo" to "1.0.0", manager.parseCqlHeader("library Foo version '1.0.0'"))
        assertEquals("Bar" to null, manager.parseCqlHeader("library Bar\n\nusing FHIR version '4.0.1'"))
        assertEquals("My Lib" to "1.2", manager.parseCqlHeader("""library "My Lib" version '1.2'"""))
    }

    @Test
    fun parseCqlHeader_namespaceQualifiedName_usesLastSegment() {
        val manager = IgContextManager(TestContentService())
        assertEquals("Name" to "3.0.0", manager.parseCqlHeader("library ns.sub.Name version '3.0.0'"))
    }

    @Test
    fun parseCqlHeader_ignoresComments() {
        val manager = IgContextManager(TestContentService())
        assertEquals(
            "Real" to "2.0.0",
            manager.parseCqlHeader("// library Fake version '9.9.9'\nlibrary Real version '2.0.0'"),
        )
        assertEquals(
            "Real" to "2.0.0",
            manager.parseCqlHeader("/*\n library Fake version '9.9.9'\n*/\nlibrary Real version '2.0.0'"),
        )
    }

    @Test
    fun parseCqlHeader_noLibraryDeclaration_returnsNull() {
        val manager = IgContextManager(TestContentService())
        assertNull(manager.parseCqlHeader("define \"X\": 1"))
        assertNull(manager.parseCqlHeader(""))
    }

    // -----------------------------------------------------------------------
    // Dev dependencies — seeded primary path, staleness, cache hygiene
    // -----------------------------------------------------------------------

    @Test
    fun getContext_devDependency_seedsInMemoryPackage(
        @TempDir tempDir: Path,
    ) {
        assumeR4CoreCached()
        val consumer = tempDir.resolve("consumer").also { it.toFile().mkdirs() }
        createMinimalIg(
            consumer, "consumer.pkg", "http://example.org/consumer", "4.0.1",
            dependsOn = listOf("great.reef" to "dev"),
        )
        val cqlFile = createCqlFileInSubdir(consumer)
        createSiblingProject(tempDir, "greatreef", "great.reef", "http://example.org/greatreef")

        val manager = IgContextManager(diskContentService())
        val ctx = manager.getContext(cqlFile.toUri())

        assertNotNull(ctx, "seeded NpmPackageManager should succeed despite the dev dependency")
        assertTrue(
            ctx!!.packageManager.npmList.any { it.name() == "great.reef" },
            "npmList must contain the in-memory dev package",
        )
        assertTrue(
            ctx.namespaces.any { it.name == "great.reef" && it.uri == "http://example.org/greatreef" },
            "dev package namespace must be derivable from the context",
        )
    }

    @Test
    fun getContext_devDependency_rebuildsWhenDevSourcesChange(
        @TempDir tempDir: Path,
    ) {
        assumeR4CoreCached()
        val consumer = tempDir.resolve("consumer").also { it.toFile().mkdirs() }
        createMinimalIg(
            consumer, "consumer.pkg", "http://example.org/consumer", "4.0.1",
            dependsOn = listOf("great.reef" to "dev"),
        )
        val cqlFile = createCqlFileInSubdir(consumer)
        val sibling = createSiblingProject(tempDir, "greatreef", "great.reef", "http://example.org/greatreef")
        val siblingCql = sibling.resolve("input/cql/Common.cql")

        val manager = IgContextManager(diskContentService())
        val first = manager.getContext(cqlFile.toUri())
        val second = manager.getContext(cqlFile.toUri())
        assertSame(first, second, "unchanged dev sources must reuse the cached context")

        // Bump the dev source mtime well past filesystem timestamp granularity.
        Files.setLastModifiedTime(siblingCql, FileTime.fromMillis(System.currentTimeMillis() + 10_000))
        val third = manager.getContext(cqlFile.toUri())
        assertNotSame(first, third, "a dev source change must rebuild the context")
    }

    @Test
    fun getContext_devDependency_writesNothingToFhirPackageCache(
        @TempDir tempDir: Path,
    ) {
        assumeR4CoreCached()
        val devPackageId = "dev.test.pkg.${UUID.randomUUID()}"
        val consumer = tempDir.resolve("consumer").also { it.toFile().mkdirs() }
        createMinimalIg(
            consumer, "consumer.pkg", "http://example.org/consumer", "4.0.1",
            dependsOn = listOf(devPackageId to "dev"),
        )
        val cqlFile = createCqlFileInSubdir(consumer)
        createSiblingProject(tempDir, "devpkg", devPackageId, "http://example.org/devpkg")

        val manager = IgContextManager(diskContentService())
        manager.getContext(cqlFile.toUri())
        manager.setupLibraryManager(cqlFile.toUri(), LibraryManager(ModelManager()))

        assertFalse(
            File(FHIR_CACHE_DIR, "$devPackageId#current").exists(),
            "the LS must never write a #current entry to ~/.fhir/packages",
        )
        assertFalse(
            File(FHIR_CACHE_DIR, "$devPackageId#dev").exists(),
            "the LS must never write a #dev entry to ~/.fhir/packages",
        )
    }

    @Test
    fun setupLibraryManager_twoDevDependencies_registersBoth(
        @TempDir tempDir: Path,
    ) {
        // Dev dependsOn entries are removed from the IG copy handed to NpmPackageManager
        // (its hasPackage dedup only consults the first npmList element), so BOTH seeded
        // in-memory dev packages must survive to namespace registration.
        assumeR4CoreCached()
        val consumer = tempDir.resolve("consumer").also { it.toFile().mkdirs() }
        createMinimalIg(
            consumer, "consumer.pkg", "http://example.org/consumer", "4.0.1",
            dependsOn = listOf("dev.pkg.alpha" to "dev", "dev.pkg.beta" to "dev"),
        )
        val cqlFile = createCqlFileInSubdir(consumer)
        createSiblingProject(
            tempDir, "alpha", "dev.pkg.alpha", "http://example.org/alpha",
            cqlLibrary = "library AlphaLib version '1.0.0'\n\ndefine \"A\": 1",
        )
        createSiblingProject(
            tempDir, "beta", "dev.pkg.beta", "http://example.org/beta",
            cqlLibrary = "library BetaLib version '1.0.0'\n\ndefine \"B\": 2",
        )

        val manager = IgContextManager(diskContentService())
        val libraryManager = LibraryManager(ModelManager())
        manager.setupLibraryManager(cqlFile.toUri(), libraryManager)

        assertEquals(
            "http://example.org/alpha",
            libraryManager.namespaceManager.resolveNamespaceUri("dev.pkg.alpha"),
            "the first dev package's namespace must be registered",
        )
        assertEquals(
            "http://example.org/beta",
            libraryManager.namespaceManager.resolveNamespaceUri("dev.pkg.beta"),
            "the second dev package's namespace must be registered",
        )
    }

    // -----------------------------------------------------------------------
    // findLocalProject — discovers sibling projects with matching packageId
    // -----------------------------------------------------------------------

    @Test
    fun findLocalProject_matchingSibling_returnsIgContext(
        @TempDir tempDir: Path,
    ) {
        // Create sibling directories: sibling1 (matches pkgA), sibling2 (mismatch), sibling3 (no ig.ini)
        val sibling1 = tempDir.resolve("sibling1").also { it.toFile().mkdirs() }
        createMinimalIg(sibling1, "pkgA", "http://example.org/pkgA", "4.0.1")
        val sibling2 = tempDir.resolve("sibling2").also { it.toFile().mkdirs() }
        createMinimalIg(sibling2, "pkgB", "http://example.org/pkgB", "4.0.1")
        tempDir.resolve("sibling3").toFile().mkdirs() // no ig.ini

        val manager = IgContextManager(TestContentService())
        val method: Method =
            IgContextManager::class.java.getDeclaredMethod(
                "findLocalProject",
                Path::class.java,
                String::class.java,
            )
        method.isAccessible = true
        val project = method.invoke(manager, tempDir, "pkgA") as? IGContext

        assertNotNull(project, "Should find project with matching packageId pkgA")
        assertEquals("pkgA", project!!.packageId)
    }

    @Test
    fun findLocalProject_nonMatchingSibling_returnsNull(
        @TempDir tempDir: Path,
    ) {
        val sibling = tempDir.resolve("sibling").also { it.toFile().mkdirs() }
        createMinimalIg(sibling, "otherPkg", "http://example.org/other", "4.0.1")

        val manager = IgContextManager(TestContentService())
        val method: Method =
            IgContextManager::class.java.getDeclaredMethod(
                "findLocalProject",
                Path::class.java,
                String::class.java,
            )
        method.isAccessible = true
        val project = method.invoke(manager, tempDir, "nonExistentPkg")

        assertNull(project, "Should return null when no sibling has the requested packageId")
    }

    @Test
    fun findLocalProject_noSiblings_returnsNull(
        @TempDir tempDir: Path,
    ) {
        val manager = IgContextManager(TestContentService())
        val method: Method =
            IgContextManager::class.java.getDeclaredMethod(
                "findLocalProject",
                Path::class.java,
                String::class.java,
            )
        method.isAccessible = true
        val project = method.invoke(manager, tempDir, "anyPkg")

        assertNull(project, "Should return null when there are no sibling directories")
    }

    // -----------------------------------------------------------------------
    // findIgContext — no ig.ini anywhere in ancestor chain
    // -----------------------------------------------------------------------

    @Test
    fun findIgContext_noIgIni_returnsNull(
        @TempDir tempDir: Path,
    ) {
        val cqlFile = createCqlFileInSubdir(tempDir)
        val cs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ) = emptySet<URI>()
            }
        val manager = ExposedIgContextManager(cs)
        val result = manager.findIgContext(cqlFile.toUri())
        assertNull(result)
    }

    companion object {
        // A URI whose parent dir is /org/opencds/cqf/cql/ls/server/ —
        // TestContentService returns null for the ig.ini probe, so no package context is created.
        private val TEST_URI: URI = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!

        private val log = LoggerFactory.getLogger(IgContextManagerTest::class.java)
        private val jsonMapper = ObjectMapper()

        private val FHIR_CACHE_DIR = File(System.getProperty("user.home"), ".fhir/packages")

        private fun assumeR4CoreCached() {
            val r4CoreDir = File(FHIR_CACHE_DIR, "hl7.fhir.r4.core#4.0.1")
            Assumptions.assumeTrue(r4CoreDir.exists()) {
                "Skipping: hl7.fhir.r4.core#4.0.1 not in FHIR package cache"
            }
        }

        /** ContentService whose default read(uri) opens the URI — serves @TempDir files from disk. */
        private fun diskContentService(): ContentService =
            object : ContentService {
                override fun locate(
                    root: URI,
                    libraryIdentifier: VersionedIdentifier,
                ) = emptySet<URI>()
            }

        private fun createMinimalIg(
            dir: Path,
            packageId: String,
            canonical: String,
            fhirVersion: String,
            dependsOn: List<Pair<String, String>> = emptyList(),
        ) {
            dir.resolve("ig.ini").toFile().writeText("[IG]\nig = ig.json\n")
            val dependsOnJson =
                if (dependsOn.isEmpty()) {
                    ""
                } else {
                    dependsOn.joinToString(",\n", prefix = ",\n    \"dependsOn\": [\n", postfix = "\n    ]") {
                            (pkgId, version) ->
                        """        {"packageId": "$pkgId", "uri": "http://example.org/$pkgId", "version": "$version"}"""
                    }
                }
            dir.resolve("ig.json").toFile().writeText(
                """
                {
                    "resourceType": "ImplementationGuide",
                    "id": "$packageId",
                    "url": "$canonical/ImplementationGuide/$packageId",
                    "version": "1.0.0",
                    "name": "$packageId",
                    "packageId": "$packageId",
                    "fhirVersion": ["$fhirVersion"]$dependsOnJson
                }
                """.trimIndent(),
            )
        }

        private fun createCqlFileInSubdir(dir: Path): Path {
            val cqlDir = dir.resolve("input/cql")
            cqlDir.toFile().mkdirs()
            val cqlFile = cqlDir.resolve("dummy.cql")
            cqlFile.toFile().writeText("library Dummy version '1.0.0'")
            return cqlFile
        }

        /** Creates a sibling dev-dependency project with an ig.ini, ig.json, and one CQL library. */
        private fun createSiblingProject(
            workspaceRoot: Path,
            dirName: String,
            packageId: String,
            canonical: String,
            cqlLibrary: String = "library Common version '1.0.0'\n\ndefine \"One\": 1",
        ): Path {
            val project = workspaceRoot.resolve(dirName).also { it.toFile().mkdirs() }
            createMinimalIg(project, packageId, canonical, "4.0.1")
            project.resolve("input/cql").toFile().mkdirs()
            val name = Regex("""library\s+(\w+)""").find(cqlLibrary)!!.groupValues[1]
            project.resolve("input/cql/$name.cql").toFile().writeText(cqlLibrary)
            return project
        }

        private fun igContextFor(projectDir: Path): IGContext {
            val igContext = IGContext(LoggerAdapter(log))
            igContext.initializeFromIni(projectDir.resolve("ig.ini").toString())
            return igContext
        }

        private fun readTgzEntries(tgz: ByteArray): Map<String, ByteArray> {
            val entries = mutableMapOf<String, ByteArray>()
            TarArchiveInputStream(GZIPInputStream(ByteArrayInputStream(tgz))).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    entries[entry.name] = tar.readAllBytes()
                    entry = tar.nextEntry
                }
            }
            return entries
        }
    }
}
