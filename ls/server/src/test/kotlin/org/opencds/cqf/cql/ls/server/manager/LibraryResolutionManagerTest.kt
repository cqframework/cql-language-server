package org.opencds.cqf.cql.ls.server.manager

import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.ModelManager
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.WorkspaceFolder
import org.hl7.cql.model.NamespaceInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger

class LibraryResolutionManagerTest {
    // -----------------------------------------------------------------------
    // Test helper
    //
    // Override readIgContextInfo to inject controlled namespace data without
    // real ig.ini / IG JSON files. infoByDir maps a directory absolute path
    // to the (packageId, canonicalBase) pair that ig.ini in that dir provides.
    // shouldThrow lists directory paths where readIgContextInfo should throw,
    // simulating a corrupt or unreadable ig.ini.
    // -----------------------------------------------------------------------

    private fun manager(
        vararg folders: File,
        infoByDir: Map<String, Pair<String, String>> = emptyMap(),
        shouldThrow: Set<String> = emptySet(),
    ): LibraryResolutionManager {
        val workspaceFolders = folders.map { WorkspaceFolder(it.toURI().toString(), it.name) }
        return object : LibraryResolutionManager(workspaceFolders) {
            override fun readIgContextInfo(igIniFile: File): Pair<String, String>? {
                if (igIniFile.parentFile.absolutePath in shouldThrow) {
                    error("simulated ig.ini parse failure")
                }
                return infoByDir[igIniFile.parentFile.absolutePath]
            }
        }
    }

    // -----------------------------------------------------------------------
    // igProjects — empty workspace
    // -----------------------------------------------------------------------

    @Test
    fun igProjects_emptyWorkspace_returnsEmpty() {
        val m = manager()
        assertTrue(m.igProjects().isEmpty())
    }

    // -----------------------------------------------------------------------
    // resolveCanonicalUrl — empty workspace
    // -----------------------------------------------------------------------

    @Test
    fun resolveCanonicalUrl_emptyWorkspace_returnsNull() {
        val m = manager()
        assertNull(m.resolveCanonicalUrl("https://example.org"))
    }

    // -----------------------------------------------------------------------
    // igProjects — folder with no ig.ini
    // -----------------------------------------------------------------------

    @Test
    fun igProjects_folderWithNoIgIni_returnsEmpty(
        @TempDir tempDir: File,
    ) {
        val m = manager(tempDir)
        assertTrue(m.igProjects().isEmpty())
    }

    // -----------------------------------------------------------------------
    // igProjects — ig.ini at the workspace folder root
    // -----------------------------------------------------------------------

    @Test
    fun igProjects_igIniAtFolderRoot_returnsThatFolder(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "ig.ini").writeText("")
        val m =
            manager(
                tempDir,
                infoByDir = mapOf(tempDir.absolutePath to ("test.pkg" to "https://example.org")),
            )

        val projects = m.igProjects()
        assertEquals(1, projects.size)
        assertEquals(tempDir.toURI().toString(), projects[0].uri)
    }

    // -----------------------------------------------------------------------
    // igProjects — ig.ini only in a subdirectory (multi-project layout)
    // The subdirectory is returned as a synthetic WorkspaceFolder; the root is not.
    // -----------------------------------------------------------------------

    @Test
    fun igProjects_igIniInSubdirOnly_returnsSubdir(
        @TempDir tempDir: File,
    ) {
        val sub = File(tempDir, "CommonLibs").also { it.mkdirs() }
        File(sub, "ig.ini").writeText("")
        val m =
            manager(
                tempDir,
                infoByDir = mapOf(sub.absolutePath to ("common.pkg" to "https://common.example.org")),
            )

        val projects = m.igProjects()
        assertEquals(1, projects.size)
        assertEquals(sub.toURI().toString(), projects[0].uri)
        assertEquals("CommonLibs", projects[0].name)
    }

    // -----------------------------------------------------------------------
    // igProjects — ig.ini nested more than one level deep is NOT discovered.
    // buildNamespaceIndex scans only root + immediate subdirectories.
    // -----------------------------------------------------------------------

    @Test
    fun igProjects_igIniInGrandchild_notDiscovered(
        @TempDir tempDir: File,
    ) {
        val child = File(tempDir, "child").also { it.mkdirs() }
        val grandchild = File(child, "grandchild").also { it.mkdirs() }
        File(grandchild, "ig.ini").writeText("")
        val m =
            manager(
                tempDir,
                infoByDir = mapOf(grandchild.absolutePath to ("pkg" to "https://example.org")),
            )

        assertTrue(m.igProjects().isEmpty(), "ig.ini nested more than one level deep should not be discovered")
    }

    // -----------------------------------------------------------------------
    // igProjects — malformed workspace folder URI is silently skipped
    // -----------------------------------------------------------------------

    @Test
    fun igProjects_malformedWorkspaceFolderUri_skipsFolder() {
        val m =
            object : LibraryResolutionManager(listOf(WorkspaceFolder("not a valid URI !!!", "bad"))) {
                override fun readIgContextInfo(igIniFile: File): Pair<String, String>? = null
            }

        assertDoesNotThrow { m.igProjects() }
        assertTrue(m.igProjects().isEmpty())
    }

    // -----------------------------------------------------------------------
    // igProjects — ig.ini at root AND in a subdirectory → both returned
    // -----------------------------------------------------------------------

    @Test
    fun igProjects_igIniAtRootAndSubdir_returnsBoth(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "ig.ini").writeText("")
        val sub = File(tempDir, "shared").also { it.mkdirs() }
        File(sub, "ig.ini").writeText("")
        val m =
            manager(
                tempDir,
                infoByDir =
                    mapOf(
                        tempDir.absolutePath to ("root.pkg" to "https://root.example.org"),
                        sub.absolutePath to ("shared.pkg" to "https://shared.example.org"),
                    ),
            )

        assertEquals(2, m.igProjects().size)
    }

    // -----------------------------------------------------------------------
    // resolveCanonicalUrl — matching project returns input/cql URI
    // -----------------------------------------------------------------------

    @Test
    fun resolveCanonicalUrl_matchingCanonical_returnsInputCqlUri(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "ig.ini").writeText("")
        val m =
            manager(
                tempDir,
                infoByDir = mapOf(tempDir.absolutePath to ("test.pkg" to "https://example.org/ig")),
            )

        val resolved = m.resolveCanonicalUrl("https://example.org/ig")
        assertNotNull(resolved)
        assertEquals(tempDir.toPath().resolve("input/cql"), Paths.get(resolved!!))
    }

    // -----------------------------------------------------------------------
    // resolveCanonicalUrl — non-matching URL returns null
    // -----------------------------------------------------------------------

    @Test
    fun resolveCanonicalUrl_noMatch_returnsNull(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "ig.ini").writeText("")
        val m =
            manager(
                tempDir,
                infoByDir = mapOf(tempDir.absolutePath to ("test.pkg" to "https://example.org/ig")),
            )

        assertNull(m.resolveCanonicalUrl("https://different.org/other"))
    }

    // -----------------------------------------------------------------------
    // getInputDirectories — empty workspace returns empty list
    // -----------------------------------------------------------------------

    @Test
    fun getInputDirectories_emptyWorkspace_returnsEmptyList() {
        assertTrue(manager().getInputDirectories().isEmpty())
    }

    // -----------------------------------------------------------------------
    // getInputDirectories — one project returns its input/ path
    // -----------------------------------------------------------------------

    @Test
    fun getInputDirectories_oneProject_returnsInputPath(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "ig.ini").writeText("")
        val m =
            manager(
                tempDir,
                infoByDir = mapOf(tempDir.absolutePath to ("test.pkg" to "https://example.org/ig")),
            )

        val dirs = m.getInputDirectories()
        assertEquals(1, dirs.size)
        assertEquals(tempDir.toPath().resolve("input"), dirs[0])
    }

    // -----------------------------------------------------------------------
    // getInputDirectories — two projects return two distinct input/ paths
    // -----------------------------------------------------------------------

    @Test
    fun getInputDirectories_twoProjects_returnsTwoPaths(
        @TempDir dir1: File,
        @TempDir dir2: File,
    ) {
        File(dir1, "ig.ini").writeText("")
        File(dir2, "ig.ini").writeText("")
        val m =
            manager(
                dir1,
                dir2,
                infoByDir =
                    mapOf(
                        dir1.absolutePath to ("pkg.one" to "https://one.example.org"),
                        dir2.absolutePath to ("pkg.two" to "https://two.example.org"),
                    ),
            )

        val dirs = m.getInputDirectories()
        assertEquals(2, dirs.size)
        assertTrue(dirs.contains(dir1.toPath().resolve("input")))
        assertTrue(dirs.contains(dir2.toPath().resolve("input")))
    }

    // -----------------------------------------------------------------------
    // registerWorkspaceNamespaces — single namespace, no prior registrations
    // -----------------------------------------------------------------------

    @Test
    fun registerWorkspaceNamespaces_singleEntry_registersNamespace(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "ig.ini").writeText("")
        val m =
            manager(
                tempDir,
                infoByDir = mapOf(tempDir.absolutePath to ("my.package" to "https://my.org/ig")),
            )
        val libraryManager = LibraryManager(ModelManager())

        m.registerWorkspaceNamespaces(libraryManager)

        assertEquals(
            "https://my.org/ig",
            libraryManager.namespaceManager.resolveNamespaceUri("my.package"),
            "Package ID should resolve to the canonical base URL after registration",
        )
    }

    // -----------------------------------------------------------------------
    // registerWorkspaceNamespaces — URI collision (same canonical URL already
    // registered under a different name) must not throw.
    //
    // This tests the IllegalStateException catch added for the case where
    // IgContextManager.setupLibraryManager (via NpmProcessor) already registered
    // a namespace whose URI matches a workspace project's canonical base.
    // NamespaceManager.addNamespace throws IllegalStateException when a URI is
    // already registered under a different name (Kotlin check() convention).
    // -----------------------------------------------------------------------

    @Test
    fun registerWorkspaceNamespaces_uriCollision_doesNotThrow(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "ig.ini").writeText("")
        val m =
            manager(
                tempDir,
                // workspace project "pkgB" claims the same canonical URL already held by "pkgA"
                infoByDir = mapOf(tempDir.absolutePath to ("pkgB" to "https://example.org")),
            )

        // Pre-register pkgA → https://example.org (simulates npm setup having run first)
        val libraryManager = LibraryManager(ModelManager())
        libraryManager.namespaceManager.addNamespace(NamespaceInfo("pkgA", "https://example.org"))

        // pkgB's registration attempt would collide — must be silently skipped
        assertDoesNotThrow { m.registerWorkspaceNamespaces(libraryManager) }

        // First registration wins
        assertEquals("pkgA", libraryManager.namespaceManager.getNamespaceInfoFromUri("https://example.org")?.name)
    }

    // -----------------------------------------------------------------------
    // registerWorkspaceNamespaces — empty workspace does nothing
    // -----------------------------------------------------------------------

    @Test
    fun registerWorkspaceNamespaces_emptyWorkspace_doesNotThrow() {
        val m = manager()
        assertDoesNotThrow { m.registerWorkspaceNamespaces(LibraryManager(ModelManager())) }
    }

    // -----------------------------------------------------------------------
    // onMessageEvent — ig.ini change invalidates the index
    // -----------------------------------------------------------------------

    @Test
    fun onMessageEvent_igIniChanged_rebuildsIndexOnNextCall(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "ig.ini").writeText("")
        val callCount = AtomicInteger(0)
        val m =
            object : LibraryResolutionManager(listOf(WorkspaceFolder(tempDir.toURI().toString(), "root"))) {
                override fun readIgContextInfo(igIniFile: File): Pair<String, String> {
                    callCount.incrementAndGet()
                    return "pkg" to "https://example.org"
                }
            }

        m.igProjects() // builds index
        val after1 = callCount.get()

        m.onMessageEvent(
            DidChangeWatchedFilesEvent(
                DidChangeWatchedFilesParams(
                    listOf(FileEvent("file:///any/path/ig.ini", FileChangeType.Changed)),
                ),
            ),
        )

        m.igProjects() // index invalidated → rebuilt
        assertTrue(
            callCount.get() > after1,
            "Expected readIgContextInfo to be called again after ig.ini change event",
        )
    }

    // -----------------------------------------------------------------------
    // onMessageEvent — unrelated file change does NOT invalidate the index
    // -----------------------------------------------------------------------

    @Test
    fun onMessageEvent_unrelatedFileChanged_doesNotInvalidateIndex(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "ig.ini").writeText("")
        val callCount = AtomicInteger(0)
        val m =
            object : LibraryResolutionManager(listOf(WorkspaceFolder(tempDir.toURI().toString(), "root"))) {
                override fun readIgContextInfo(igIniFile: File): Pair<String, String> {
                    callCount.incrementAndGet()
                    return "pkg" to "https://example.org"
                }
            }

        m.igProjects() // builds index
        val after1 = callCount.get()

        m.onMessageEvent(
            DidChangeWatchedFilesEvent(
                DidChangeWatchedFilesParams(
                    listOf(FileEvent("file:///any/path/SomeLibrary.cql", FileChangeType.Changed)),
                ),
            ),
        )

        m.igProjects() // should still be cached
        assertEquals(after1, callCount.get(), "Unrelated file change should not rebuild the namespace index")
    }

    // -----------------------------------------------------------------------
    // buildNamespaceIndex — readIgContextInfo throws → folder silently skipped,
    // other folders still indexed
    // -----------------------------------------------------------------------

    @Test
    fun buildNamespaceIndex_readIgContextInfoThrows_skipsFolder(
        @TempDir tempDir: File,
    ) {
        val bad =
            File(tempDir, "bad").also {
                it.mkdirs()
                File(it, "ig.ini").writeText("")
            }
        val good =
            File(tempDir, "good").also {
                it.mkdirs()
                File(it, "ig.ini").writeText("")
            }

        // Use a workspace folder that contains both subdirs
        val m =
            manager(
                tempDir,
                infoByDir = mapOf(good.absolutePath to ("good.pkg" to "https://good.example.org")),
                shouldThrow = setOf(bad.absolutePath),
            )

        val projects = m.igProjects()
        assertEquals(1, projects.size, "Only the non-throwing folder should be indexed")
        assertEquals(good.toURI().toString(), projects[0].uri)
    }

    // -----------------------------------------------------------------------
    // buildNamespaceIndex — readIgContextInfo returns null (missing field) →
    // folder silently skipped
    // -----------------------------------------------------------------------

    @Test
    fun buildNamespaceIndex_readIgContextInfoReturnsNull_skipsFolder(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "ig.ini").writeText("")
        // infoByDir is empty → readIgContextInfo returns null for every dir
        val m = manager(tempDir)

        assertTrue(m.igProjects().isEmpty(), "Folder with null readIgContextInfo result should be skipped")
    }

    // -----------------------------------------------------------------------
    // buildNamespaceIndex — two workspace folders indexed independently
    // -----------------------------------------------------------------------

    @Test
    fun buildNamespaceIndex_twoWorkspaceFolders_bothIndexed(
        @TempDir dir1: File,
        @TempDir dir2: File,
    ) {
        File(dir1, "ig.ini").writeText("")
        File(dir2, "ig.ini").writeText("")
        val m =
            manager(
                dir1,
                dir2,
                infoByDir =
                    mapOf(
                        dir1.absolutePath to ("pkg.one" to "https://one.example.org"),
                        dir2.absolutePath to ("pkg.two" to "https://two.example.org"),
                    ),
            )

        assertEquals(2, m.igProjects().size)
        assertNotNull(m.resolveCanonicalUrl("https://one.example.org"))
        assertNotNull(m.resolveCanonicalUrl("https://two.example.org"))
    }
}
