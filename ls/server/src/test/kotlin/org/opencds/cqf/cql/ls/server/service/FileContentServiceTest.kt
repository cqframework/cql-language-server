package org.opencds.cqf.cql.ls.server.service

import org.eclipse.lsp4j.WorkspaceFolder
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.cql.ls.server.manager.JsonLibraryResolutionConfigProvider
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionConfig
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionConfigProvider
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import java.io.File
import java.net.URI

class FileContentServiceTest {
    @TempDir
    lateinit var tempDir: File

    // ── Test helpers ────────────────────────────────────────────────────────

    /** Returns defaults for every root — no disk access needed for most tests. */
    private val noOpConfigProvider =
        object : LibraryResolutionConfigProvider {
            override fun getConfig(root: URI) = LibraryResolutionConfig()
        }

    private fun folder(dir: File): WorkspaceFolder =
        WorkspaceFolder().also {
            it.uri = dir.toURI().toString()
            it.name = dir.name
        }

    private fun manager(vararg dirs: File): LibraryResolutionManager =
        LibraryResolutionManager(dirs.map { folder(it) })

    private fun svc(vararg dirs: File): FileContentService =
        FileContentService(dirs.map { folder(it) }, noOpConfigProvider, manager(*dirs))

    private fun id(
        name: String,
        version: String,
    ) = VersionedIdentifier().withId(name).withVersion(version)

    private fun id(name: String) = VersionedIdentifier().withId(name)

    private fun idWithSystem(
        name: String,
        system: String,
        version: String? = null,
    ) = VersionedIdentifier().withId(name).withSystem(system).let {
        if (version != null) it.withVersion(version) else it
    }

    /** Manager with a hard-coded namespace mapping — avoids real ig.ini parsing. */
    private fun managerWithNamespace(
        canonicalUrl: String,
        inputCqlDir: File,
        vararg dirs: File,
    ): LibraryResolutionManager =
        object : LibraryResolutionManager(dirs.map { folder(it) }) {
            override fun resolveCanonicalUrl(url: String): URI? =
                if (url == canonicalUrl) inputCqlDir.toURI() else null

            override fun igProjects(): List<WorkspaceFolder> = dirs.map { folder(it) }
        }

    // ── searchFolder (companion — backward compat) ───────────────────────────

    @Test
    fun searchFolder_exactVersionedMatch_returnsFile() {
        val cqlFile = File(tempDir, "FHIRHelpers-4.0.1.cql").also { it.createNewFile() }

        val result = FileContentService.searchFolder(tempDir.toURI(), id("FHIRHelpers", "4.0.1"))

        assertNotNull(result)
        assertEquals(cqlFile.canonicalPath, result!!.canonicalPath)
    }

    @Test
    fun searchFolder_unversionedFilename_treatedAsNearestMatch() {
        val cqlFile = File(tempDir, "FHIRHelpers.cql").also { it.createNewFile() }

        val result = FileContentService.searchFolder(tempDir.toURI(), id("FHIRHelpers", "4.0.1"))

        assertNotNull(result)
        assertEquals(cqlFile.canonicalPath, result!!.canonicalPath)
    }

    @Test
    fun searchFolder_noMatch_returnsNull() {
        val result = FileContentService.searchFolder(tempDir.toURI(), id("NonExistent", "1.0.0"))
        assertNull(result)
    }

    @Test
    fun searchFolder_compatibleVersion_returnsNearest() {
        File(tempDir, "FHIRHelpers-4.0.1.cql").createNewFile()

        val result = FileContentService.searchFolder(tempDir.toURI(), id("FHIRHelpers", "4.0.0"))

        assertNotNull(result)
    }

    @Test
    fun searchFolder_noMatchingPrefix_returnsNull() {
        File(tempDir, "SomeOtherLib.cql").createNewFile()

        val result = FileContentService.searchFolder(tempDir.toURI(), id("MyLib", "1.0.0"))

        assertNull(result)
    }

    // ── read(URI) ────────────────────────────────────────────────────────────

    @Test
    fun read_existingFile_returnsStream() {
        val cqlFile = File(tempDir, "Test.cql").also { it.createNewFile() }

        val stream = svc(tempDir).read(cqlFile.toURI())

        assertNotNull(stream)
        stream!!.close()
    }

    @Test
    fun read_nonExistentFile_returnsNull() {
        val uri: URI = File(tempDir, "does-not-exist.cql").toURI()

        assertNull(svc().read(uri))
    }

    // ── locate — basic workspace containment (preserves original behavior) ───

    @Test
    fun locate_rootWithinWorkspace_returnsUri() {
        File(tempDir, "One.cql").createNewFile()

        val result = svc(tempDir).locate(tempDir.toURI(), id("One"))

        assertTrue(result.size >= 1)
    }

    @Test
    fun locate_rootOutsideWorkspace_returnsEmpty() {
        val workspace = File(tempDir, "workspace").also { it.mkdirs() }
        File(workspace, "One.cql").createNewFile()

        // root = tempDir, which is NOT inside the workspace subfolder
        val result = svc(workspace).locate(tempDir.toURI(), id("One"))

        assertTrue(result.isEmpty())
    }

    // ── BFS traversal ────────────────────────────────────────────────────────

    @Test
    fun locate_exactVersionAtDepth1_beatsCompatibleAtDepth0() {
        // Depth 0: compatible match only (4.0.1 is patch-flexible for request 4.0.0)
        File(tempDir, "Lib-4.0.1.cql").createNewFile()
        // Depth 1: exact match for 4.0.0
        val sub = File(tempDir, "sub").also { it.mkdirs() }
        val exact = File(sub, "Lib-4.0.0.cql").also { it.createNewFile() }

        // Pass 1 (exact) returns depth-1 before pass 2 (compatible) would return depth-0
        val result = svc(tempDir).locate(tempDir.toURI(), id("Lib", "4.0.0"))

        assertEquals(setOf(exact.toURI()), result)
    }

    @Test
    fun locate_shallowestExactWins_whenExactAtMultipleDepths() {
        val shallow = File(tempDir, "Lib-1.0.0.cql").also { it.createNewFile() }
        val sub = File(tempDir, "sub").also { it.mkdirs() }
        File(sub, "Lib-1.0.0.cql").createNewFile()

        val result = svc(tempDir).locate(tempDir.toURI(), id("Lib", "1.0.0"))

        assertEquals(setOf(shallow.toURI()), result)
    }

    @Test
    fun locate_noVersionSpecified_returnsNewestAtShallowestDepth() {
        // Two files at depth 0 — should return the newer version (2.0.0 > 1.0.0)
        File(tempDir, "Lib-1.0.0.cql").createNewFile()
        val newer = File(tempDir, "Lib-2.0.0.cql").also { it.createNewFile() }

        val result = svc(tempDir).locate(tempDir.toURI(), id("Lib"))

        assertEquals(setOf(newer.toURI()), result)
    }

    // ── Tier 2 (cross-directory, same project) ───────────────────────────────

    @Test
    fun locate_dependencyAtInputCqlRoot_foundWhenRequestingLibraryIsInSubdir() {
        // Project layout: workspace/input/cql/measures/ includes Shared.cql
        // Shared.cql lives at workspace/input/cql/ (tier 2)
        val inputCql = File(tempDir, "input/cql").also { it.mkdirs() }
        val measures = File(inputCql, "measures").also { it.mkdirs() }
        val shared = File(inputCql, "Shared-1.0.0.cql").also { it.createNewFile() }

        val result = svc(tempDir).locate(measures.toURI(), id("Shared", "1.0.0"))

        assertEquals(setOf(shared.toURI()), result)
    }

    @Test
    fun locate_tier1BeforeTier2_whenBothHaveExactMatch() {
        val inputCql = File(tempDir, "input/cql").also { it.mkdirs() }
        val sub = File(inputCql, "sub").also { it.mkdirs() }

        // Tier 2 (input/cql root)
        File(inputCql, "Lib-1.0.0.cql").createNewFile()
        // Tier 1 (sub — requesting dir) — should win
        val tier1File = File(sub, "Lib-1.0.0.cql").also { it.createNewFile() }

        val result = svc(tempDir).locate(sub.toURI(), id("Lib", "1.0.0"))

        assertEquals(setOf(tier1File.toURI()), result)
    }

    // ── Tier 3 — disabled by default ─────────────────────────────────────────

    @Test
    fun locate_crossProject_disabledByDefault_libraryNotFoundInOtherProject() {
        val projectA = File(tempDir, "projectA").also { it.mkdirs() }
        val projectAInput = File(projectA, "input/cql").also { it.mkdirs() }
        val projectB = File(tempDir, "projectB").also { it.mkdirs() }
        val projectBInput = File(projectB, "input/cql").also { it.mkdirs() }
        File(projectBInput, "SharedLib-1.0.0.cql").createNewFile()

        // Default config (no config.json) → unqualifiedCrossProjectSearch = false
        val result = svc(projectA, projectB).locate(projectAInput.toURI(), id("SharedLib", "1.0.0"))

        assertTrue(result.isEmpty(), "Tier 3 must be disabled by default")
    }

    // ── Version matching modes ────────────────────────────────────────────────

    @Test
    fun locate_strictMode_rejectsCompatibleMatch() {
        File(tempDir, "Lib-4.0.1.cql").createNewFile()

        val configDir = File(tempDir, "input/tests").also { it.mkdirs() }
        File(configDir, "config.json").writeText("""{ "libraryResolution": "strict" }""")

        val strictConfigProvider = JsonLibraryResolutionConfigProvider(listOf(folder(tempDir)))
        val strictSvc = FileContentService(listOf(folder(tempDir)), strictConfigProvider, manager(tempDir))

        val result = strictSvc.locate(tempDir.toURI(), id("Lib", "4.0.0"))

        assertTrue(result.isEmpty(), "STRICT mode must reject patch-flexible match")
    }

    @Test
    fun locate_patchFlexible_acceptsSameMajorMinorHigherPatch() {
        val file = File(tempDir, "Lib-4.0.1.cql").also { it.createNewFile() }

        val result = svc(tempDir).locate(tempDir.toURI(), id("Lib", "4.0.0"))

        assertEquals(setOf(file.toURI()), result)
    }

    @Test
    fun locate_patchFlexible_rejectsDifferentMinor() {
        File(tempDir, "Lib-4.1.0.cql").createNewFile()

        val result = svc(tempDir).locate(tempDir.toURI(), id("Lib", "4.0.0"))

        assertTrue(result.isEmpty(), "Different minor version must not be accepted by patch-flexible mode")
    }

    @Test
    fun locate_exactVersionMatch_returnsExactFileOverCompatible() {
        File(tempDir, "Lib-4.0.1.cql").createNewFile()
        val exact = File(tempDir, "Lib-4.0.0.cql").also { it.createNewFile() }

        val result = svc(tempDir).locate(tempDir.toURI(), id("Lib", "4.0.0"))

        assertEquals(setOf(exact.toURI()), result)
    }

    // ── Non-parseable version strings (e.g. CI-build suffixes) ───────────────

    @Test
    fun locate_ciBuildVersion_matchedByStringComparison() {
        val file = File(tempDir, "USQualityCore-0.1.000-cibuild.cql").also { it.createNewFile() }

        val result = svc(tempDir).locate(tempDir.toURI(), id("USQualityCore", "0.1.000-cibuild"))

        assertEquals(setOf(file.toURI()), result)
    }

    // ── Hyphenated library names ─────────────────────────────────────────────
    // Library identifiers may contain hyphens when declared with a delimited
    // identifier (e.g. `library `SUR716-011Assertion``). The filename-to-name
    // parser must not split on a hyphen unless the trailing segment is a valid
    // semantic version (majorVersion != null).

    @Test
    fun searchFolder_hyphenatedLibraryName_unversionedFile_returnsFile() {
        // Exercises getNameAndVersion through the companion searchFolder API.
        // Without the fix, "011Assertion" is silently accepted as a version
        // and the name is truncated to "SUR716", causing a mismatch.
        val cqlFile = File(tempDir, "SUR716-011Assertion.cql").also { it.createNewFile() }

        val result = FileContentService.searchFolder(tempDir.toURI(), id("SUR716-011Assertion"))

        assertNotNull(result)
        assertEquals(cqlFile.canonicalPath, result!!.canonicalPath)
    }

    @Test
    fun locate_hyphenatedLibraryName_unversionedFile_returnsFile() {
        // Primary regression test for the "Could not load source for library
        // SUR716-011Assertion" error. getNameAndVersion must return the full
        // name when no valid version suffix is present.
        val cqlFile = File(tempDir, "SUR716-011Assertion.cql").also { it.createNewFile() }

        val result = svc(tempDir).locate(tempDir.toURI(), id("SUR716-011Assertion"))

        assertEquals(setOf(cqlFile.toURI()), result)
    }

    @Test
    fun locate_hyphenatedLibraryName_versionedFile_exactVersionRequested_returnsFile() {
        val cqlFile = File(tempDir, "SUR716-011Assertion-1.0.0.cql").also { it.createNewFile() }

        val result = svc(tempDir).locate(tempDir.toURI(), id("SUR716-011Assertion", "1.0.0"))

        assertEquals(setOf(cqlFile.toURI()), result)
    }

    @Test
    fun locate_hyphenatedLibraryName_versionedFile_noVersionRequested_returnsFile() {
        // Exercises the bfsCompatible path: getNameAndVersion must correctly
        // separate the version suffix from the hyphenated library name so the
        // name comparison succeeds.
        val cqlFile = File(tempDir, "SUR716-011Assertion-1.0.0.cql").also { it.createNewFile() }

        val result = svc(tempDir).locate(tempDir.toURI(), id("SUR716-011Assertion"))

        assertEquals(setOf(cqlFile.toURI()), result)
    }

    // ── Namespace-qualified resolution ────────────────────────────────────────

    @Test
    fun locate_namespacedInclude_resolvesLibraryInWorkspaceProject() {
        val projectA = File(tempDir, "projectA").also { it.mkdirs() }
        val projectBInput = File(tempDir, "projectB/input/cql").also { it.mkdirs() }
        val shared = File(projectBInput, "SharedLib-1.0.0.cql").also { it.createNewFile() }

        val m = managerWithNamespace("https://example.com/fhir", projectBInput, projectA)
        val svc = FileContentService(listOf(folder(projectA)), noOpConfigProvider, m)

        val result = svc.locate(projectA.toURI(), idWithSystem("SharedLib", "https://example.com/fhir", "1.0.0"))

        assertEquals(setOf(shared.toURI()), result)
    }

    @Test
    fun locate_namespacedInclude_unknownNamespace_returnsEmpty() {
        val projectA = File(tempDir, "projectA").also { it.mkdirs() }
        val m = managerWithNamespace("https://example.com/fhir", projectA)
        val svc = FileContentService(listOf(folder(projectA)), noOpConfigProvider, m)

        val result = svc.locate(projectA.toURI(), idWithSystem("SharedLib", "https://unknown.com/fhir", "1.0.0"))

        assertTrue(result.isEmpty())
    }

    @Test
    fun locate_namespacedInclude_usesExactVersionFirst() {
        val projectA = File(tempDir, "projectA").also { it.mkdirs() }
        val projectBInput = File(tempDir, "projectB/input/cql").also { it.mkdirs() }
        // Compatible but not exact
        File(projectBInput, "Lib-1.0.1.cql").createNewFile()
        // Exact match
        val exact = File(projectBInput, "Lib-1.0.0.cql").also { it.createNewFile() }

        val m = managerWithNamespace("https://example.com/fhir", projectBInput, projectA)
        val svc = FileContentService(listOf(folder(projectA)), noOpConfigProvider, m)

        val result = svc.locate(projectA.toURI(), idWithSystem("Lib", "https://example.com/fhir", "1.0.0"))

        assertEquals(setOf(exact.toURI()), result)
    }

    @Test
    fun locate_namespacedInclude_fallsBackToCompatible() {
        val projectA = File(tempDir, "projectA").also { it.mkdirs() }
        val projectBInput = File(tempDir, "projectB/input/cql").also { it.mkdirs() }
        // Only a compatible (higher patch) file exists
        val compatible = File(projectBInput, "Lib-1.0.1.cql").also { it.createNewFile() }

        val m = managerWithNamespace("https://example.com/fhir", projectBInput, projectA)
        val svc = FileContentService(listOf(folder(projectA)), noOpConfigProvider, m)

        val result = svc.locate(projectA.toURI(), idWithSystem("Lib", "https://example.com/fhir", "1.0.0"))

        assertEquals(setOf(compatible.toURI()), result)
    }

    // ── Tier 3 — cross-project search enabled ────────────────────────────────

    @Test
    fun locate_crossProject_enabledByConfig_findsLibraryInOtherProject() {
        val projectA = File(tempDir, "projectA").also { it.mkdirs() }
        val projectAInput = File(projectA, "input/cql").also { it.mkdirs() }
        val projectB = File(tempDir, "projectB").also { it.mkdirs() }
        val projectBInput = File(projectB, "input/cql").also { it.mkdirs() }
        val shared = File(projectBInput, "SharedLib-1.0.0.cql").also { it.createNewFile() }

        val configDir = File(projectA, "input/tests").also { it.mkdirs() }
        File(configDir, "config.json").writeText("""{ "unqualifiedCrossProjectSearch": true }""")
        val configProvider = JsonLibraryResolutionConfigProvider(listOf(folder(projectA), folder(projectB)))

        val m =
            object : LibraryResolutionManager(listOf(folder(projectA), folder(projectB))) {
                override fun igProjects(): List<WorkspaceFolder> = listOf(folder(projectA), folder(projectB))
            }
        val svc = FileContentService(listOf(folder(projectA), folder(projectB)), configProvider, m)

        val result = svc.locate(projectAInput.toURI(), id("SharedLib", "1.0.0"))

        assertEquals(setOf(shared.toURI()), result)
    }

    @Test
    fun locate_crossProject_projectSearchExclude_skipsExcludedProject() {
        val projectA = File(tempDir, "projectA").also { it.mkdirs() }
        val projectAInput = File(projectA, "input/cql").also { it.mkdirs() }
        val projectB = File(tempDir, "projectB").also { it.mkdirs() }
        val projectBInput = File(projectB, "input/cql").also { it.mkdirs() }
        File(projectBInput, "SharedLib-1.0.0.cql").createNewFile()

        val configDir = File(projectA, "input/tests").also { it.mkdirs() }
        File(configDir, "config.json").writeText(
            """{ "unqualifiedCrossProjectSearch": true, "projectSearchExclude": ["projectB"] }""",
        )
        val configProvider = JsonLibraryResolutionConfigProvider(listOf(folder(projectA), folder(projectB)))

        val m =
            object : LibraryResolutionManager(listOf(folder(projectA), folder(projectB))) {
                override fun igProjects(): List<WorkspaceFolder> = listOf(folder(projectA), folder(projectB))
            }
        val svc = FileContentService(listOf(folder(projectA), folder(projectB)), configProvider, m)

        val result = svc.locate(projectAInput.toURI(), id("SharedLib", "1.0.0"))

        assertTrue(result.isEmpty(), "Excluded project must not be searched")
    }

    @Test
    fun locate_crossProject_projectSearchOrder_searchesOrderedProjectFirst() {
        val projectA = File(tempDir, "projectA").also { it.mkdirs() }
        val projectAInput = File(projectA, "input/cql").also { it.mkdirs() }
        val projectB = File(tempDir, "projectB").also { it.mkdirs() }
        val projectBInput = File(projectB, "input/cql").also { it.mkdirs() }
        val projectC = File(tempDir, "projectC").also { it.mkdirs() }
        val projectCInput = File(projectC, "input/cql").also { it.mkdirs() }
        // Both B and C have the same library — order determines which is returned first
        File(projectBInput, "SharedLib-1.0.0.cql").createNewFile()
        val fromC = File(projectCInput, "SharedLib-1.0.0.cql").also { it.createNewFile() }

        val configDir = File(projectA, "input/tests").also { it.mkdirs() }
        File(configDir, "config.json").writeText(
            """{ "unqualifiedCrossProjectSearch": true, "projectSearchOrder": ["projectC"] }""",
        )
        val folders = listOf(folder(projectA), folder(projectB), folder(projectC))
        val configProvider = JsonLibraryResolutionConfigProvider(folders)

        val m =
            object : LibraryResolutionManager(folders) {
                override fun igProjects(): List<WorkspaceFolder> = folders
            }
        val svc = FileContentService(folders, configProvider, m)

        val result = svc.locate(projectAInput.toURI(), id("SharedLib", "1.0.0"))

        assertEquals(setOf(fromC.toURI()), result, "projectSearchOrder must put projectC ahead of projectB")
    }

    // ── Windows drive-letter case mismatch (findContainingFolder) ─────────────
    // On Windows, File.toURI() and File.getAbsolutePath() produce uppercase drive
    // letters (e.g. file:///C:/...) while VS Code sends lowercase (file:///c:/...).
    // findContainingFolder() must use Path.startsWith() so the case difference
    // does not prevent library resolution (FHIRHelpers blank-editor bug).

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun locate_windowsLowercaseDriveLetterFromVsCode_resolvesLibrary() {
        // Set up a real temp directory structure: <root>/input/cql/FHIRHelpers.cql
        val inputCqlDir = File(tempDir, "input/cql").also { it.mkdirs() }
        val helperFile = File(inputCqlDir, "FHIRHelpers.cql").also { it.createNewFile() }

        // WorkspaceFolder uses File.toURI() → uppercase drive letter
        val folderUri = tempDir.toURI().toString() // e.g. file:///C:/...
        val folder =
            WorkspaceFolder().also {
                it.uri = folderUri
                it.name = "root"
            }
        val svc = FileContentService(listOf(folder), noOpConfigProvider, LibraryResolutionManager(listOf(folder)))

        // VS Code sends libraryUri with lowercase drive letter (uri.toString() in TS)
        val vsCodeUri =
            folderUri.replace(Regex("file:///([A-Z]):")) { m ->
                "file:///${m.groupValues[1].lowercase()}:"
            }
        // Locate a CQL file from inside the project using the lowercased URI
        val measureFile = File(inputCqlDir, "MyMeasure.cql").also { it.createNewFile() }
        val measureUri = URI.create("$vsCodeUri${if (vsCodeUri.endsWith("/")) "" else "/"}input/cql/MyMeasure.cql")

        val result = svc.locate(measureUri, id("FHIRHelpers"))

        assertTrue(result.isNotEmpty(), "FHIRHelpers must be found even when drive-letter case differs")
        assertEquals(helperFile.toURI(), result.first())
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun locate_windowsUppercaseDriveLetterInWorkspaceFolder_resolvesLibrary() {
        // Both workspace folder (File.toURI() uppercase) and launch URI (uppercase)
        val inputCqlDir = File(tempDir, "input/cql").also { it.mkdirs() }
        val helperFile = File(inputCqlDir, "FHIRHelpers.cql").also { it.createNewFile() }
        val measureFile = File(inputCqlDir, "MyMeasure.cql").also { it.createNewFile() }

        val folder =
            WorkspaceFolder().also {
                it.uri = tempDir.toURI().toString()
                it.name = "root"
            }
        val svc = FileContentService(listOf(folder), noOpConfigProvider, LibraryResolutionManager(listOf(folder)))

        // Locate using the actual File URI (uppercase, consistent)
        val result = svc.locate(measureFile.toURI(), id("FHIRHelpers"))

        assertTrue(result.isNotEmpty(), "FHIRHelpers must be found when drive-letter case is consistent")
    }
}
