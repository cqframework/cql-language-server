package org.opencds.cqf.cql.ls.server.manager

import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.ModelManager
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
import java.io.InputStream
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

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
    // readContext — NpmProcessor construction failure → null, not exception
    // -----------------------------------------------------------------------

    @Test
    fun getContext_npmProcessorConstructionFails_returnsNull() {
        // An uninitialized IGContext has sourceIg == null.
        // NpmProcessor's init block calls `NpmPackageManager(igContext.sourceIg!!)`, which
        // throws NullPointerException.  The fix in readContext must catch this and return
        // Optional.empty() so that getContext() returns null instead of propagating the exception.
        val manager =
            object : IgContextManager(TestContentService()) {
                override fun findIgContext(uri: URI): org.cqframework.fhir.utilities.IGContext =
                    org.cqframework.fhir.utilities.IGContext()
            }
        val result = manager.getContext(TEST_URI)
        assertNull(result, "getContext should return null when NpmProcessor construction fails")
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

    companion object {
        // A URI whose parent dir is /org/opencds/cqf/cql/ls/server/ —
        // TestContentService returns null for the ig.ini probe, so NpmProcessor is never created.
        private val TEST_URI: URI = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
    }
}
