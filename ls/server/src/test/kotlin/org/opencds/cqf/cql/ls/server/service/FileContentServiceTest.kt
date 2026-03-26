package org.opencds.cqf.cql.ls.server.service

import org.eclipse.lsp4j.WorkspaceFolder
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI

class FileContentServiceTest {
    @TempDir
    lateinit var tempDir: File

    private fun id(
        name: String,
        version: String,
    ) = VersionedIdentifier().withId(name).withVersion(version)

    private fun id(name: String) = VersionedIdentifier().withId(name)

    // -----------------------------------------------------------------------
    // searchFolder (static)
    // -----------------------------------------------------------------------

    @Test
    fun searchFolder_exactVersionedMatch_returnsFile() {
        val cqlFile = File(tempDir, "FHIRHelpers-4.0.1.cql").also { it.createNewFile() }

        val result = FileContentService.searchFolder(tempDir.toURI(), id("FHIRHelpers", "4.0.1"))

        assertNotNull(result)
        assertEquals(cqlFile.canonicalPath, result!!.canonicalPath)
    }

    @Test
    fun searchFolder_unversionedFilename_treatedAsNearestMatch() {
        // A file named "FHIRHelpers.cql" (no version suffix) is returned when
        // the versioned file does not exist, as it is treated as the most-recent version.
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
        // 4.0.1 is compatible with a request for 4.0.0 (same major/minor, higher patch).
        File(tempDir, "FHIRHelpers-4.0.1.cql").createNewFile()

        val result = FileContentService.searchFolder(tempDir.toURI(), id("FHIRHelpers", "4.0.0"))

        assertNotNull(result)
    }

    @Test
    fun searchFolder_noMatchingPrefix_returnsNull() {
        // "SomeOtherLib.cql" does not start with "MyLib", so the filter never returns it.
        File(tempDir, "SomeOtherLib.cql").createNewFile()

        val result = FileContentService.searchFolder(tempDir.toURI(), id("MyLib", "1.0.0"))

        assertNull(result)
    }

    // -----------------------------------------------------------------------
    // read(URI)
    // -----------------------------------------------------------------------

    @Test
    fun read_existingFile_returnsStream() {
        val cqlFile = File(tempDir, "Test.cql").also { it.createNewFile() }

        val svc = FileContentService(emptyList())
        val stream = svc.read(cqlFile.toURI())

        assertNotNull(stream)
        stream!!.close()
    }

    @Test
    fun read_nonExistentFile_returnsNull() {
        val svc = FileContentService(emptyList())
        val uri: URI = File(tempDir, "does-not-exist.cql").toURI()

        assertNull(svc.read(uri))
    }

    // -----------------------------------------------------------------------
    // locate(URI, VersionedIdentifier)
    // -----------------------------------------------------------------------

    @Test
    fun locate_rootWithinWorkspace_returnsUri() {
        File(tempDir, "One.cql").createNewFile()

        val folder = WorkspaceFolder()
        folder.uri = tempDir.toURI().toString()
        val svc = FileContentService(listOf(folder))

        val result = svc.locate(tempDir.toURI(), id("One"))

        assertNotNull(result)
        assertTrue(result.size >= 1)
    }

    @Test
    fun locate_rootOutsideWorkspace_returnsEmpty() {
        val workspace = File(tempDir, "workspace").also { it.mkdirs() }
        File(workspace, "One.cql").createNewFile()

        val folder = WorkspaceFolder()
        folder.uri = workspace.toURI().toString()
        val svc = FileContentService(listOf(folder))

        // root = tempDir, which is NOT inside the workspace subfolder
        val result = svc.locate(tempDir.toURI(), id("One"))

        assertTrue(result.isEmpty())
    }
}
