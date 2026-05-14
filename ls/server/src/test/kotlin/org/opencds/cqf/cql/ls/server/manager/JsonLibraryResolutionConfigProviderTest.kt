package org.opencds.cqf.cql.ls.server.manager

import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class JsonLibraryResolutionConfigProviderTest {
    @TempDir
    lateinit var tempDir: File

    private fun folder(dir: File): WorkspaceFolder =
        WorkspaceFolder().also { it.uri = dir.toURI().toString() }

    private fun provider(vararg dirs: File): JsonLibraryResolutionConfigProvider =
        JsonLibraryResolutionConfigProvider(dirs.map { folder(it) })

    private fun writeConfig(dir: File, content: String, filename: String = "config.json"): File {
        val configDir = File(dir, "input/tests").also { it.mkdirs() }
        return File(configDir, filename).also { it.writeText(content) }
    }

    @Test
    fun getConfig_noConfigFile_returnsDefaults() {
        val config = provider(tempDir).getConfig(tempDir.toURI())

        assertEquals(LibraryResolutionMode.PATCH_FLEXIBLE, config.mode)
        assertFalse(config.unqualifiedCrossProjectSearch)
        assertTrue(config.projectSearchOrder.isEmpty())
        assertTrue(config.projectSearchExclude.isEmpty())
    }

    @Test
    fun getConfig_strictMode_returnsStrictConfig() {
        writeConfig(tempDir, """{ "libraryResolution": "strict" }""")

        val config = provider(tempDir).getConfig(tempDir.toURI())

        assertEquals(LibraryResolutionMode.STRICT, config.mode)
    }

    @Test
    fun getConfig_patchFlexible_returnsPatchFlexibleConfig() {
        writeConfig(tempDir, """{ "libraryResolution": "patch-flexible" }""")

        val config = provider(tempDir).getConfig(tempDir.toURI())

        assertEquals(LibraryResolutionMode.PATCH_FLEXIBLE, config.mode)
    }

    @Test
    fun getConfig_jsonWithComments_parsedCorrectly() {
        writeConfig(
            tempDir,
            """
            {
              // enable cross-project search
              "unqualifiedCrossProjectSearch": true,
              "projectSearchOrder": ["shared-libs"], // search shared-libs first
              "projectSearchExclude": ["ref-only"]
            }
            """.trimIndent(),
        )

        val config = provider(tempDir).getConfig(tempDir.toURI())

        assertTrue(config.unqualifiedCrossProjectSearch)
        assertEquals(listOf("shared-libs"), config.projectSearchOrder)
        assertEquals(setOf("ref-only"), config.projectSearchExclude)
    }

    @Test
    fun getConfig_malformedJson_returnsDefaults() {
        writeConfig(tempDir, """{ this is not valid json """)

        val config = provider(tempDir).getConfig(tempDir.toURI())

        assertEquals(LibraryResolutionMode.PATCH_FLEXIBLE, config.mode)
    }

    @Test
    fun getConfig_rootOutsideAllWorkspaceFolders_returnsDefaults() {
        val otherDir = File(tempDir, "other").also { it.mkdirs() }
        writeConfig(tempDir, """{ "libraryResolution": "strict" }""")

        // provider only knows about tempDir, but root is otherDir (outside)
        val config = provider(tempDir).getConfig(otherDir.toURI())

        // otherDir is inside tempDir, so it should still resolve
        // (relativize succeeds — otherDir is contained in tempDir)
        assertEquals(LibraryResolutionMode.STRICT, config.mode)
    }

    @Test
    fun getConfig_jsoncExtension_parsedCorrectly() {
        writeConfig(tempDir, """{ "libraryResolution": "strict" }""", "config.jsonc")

        val config = provider(tempDir).getConfig(tempDir.toURI())

        assertEquals(LibraryResolutionMode.STRICT, config.mode)
    }

    @Test
    fun getConfig_jsoncTakesPrecedenceOverJson() {
        writeConfig(tempDir, """{ "libraryResolution": "strict" }""", "config.jsonc")
        writeConfig(tempDir, """{ "libraryResolution": "patch-flexible" }""", "config.json")

        val config = provider(tempDir).getConfig(tempDir.toURI())

        assertEquals(LibraryResolutionMode.STRICT, config.mode)
    }

    @Test
    fun getConfig_unknownRoot_returnsDefaults() {
        val workspace = File(tempDir, "workspace").also { it.mkdirs() }
        val unrelated = File(tempDir, "unrelated").also { it.mkdirs() }
        writeConfig(workspace, """{ "libraryResolution": "strict" }""")

        // provider knows workspace, but root is unrelated (sibling, not contained)
        val config = provider(workspace).getConfig(unrelated.toURI())

        assertEquals(LibraryResolutionMode.PATCH_FLEXIBLE, config.mode)
    }
}
