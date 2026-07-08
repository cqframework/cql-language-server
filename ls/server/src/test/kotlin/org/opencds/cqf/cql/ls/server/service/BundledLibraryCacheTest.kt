package org.opencds.cqf.cql.ls.server.service

import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BundledLibraryCacheTest {
    @TempDir
    lateinit var cacheRoot: File

    private fun id(
        name: String,
        version: String,
    ) = VersionedIdentifier().withId(name).withVersion(version)

    private fun idNoVersion(name: String) = VersionedIdentifier().withId(name)

    // ── Known bundled resource ────────────────────────────────────────────────

    @Test
    fun resolve_knownBundledVersion_extractsFileWithRealContent() {
        val file = BundledLibraryCache.resolve(id("FHIRHelpers", "4.0.1"), cacheRoot)

        assertTrue(file != null && file.exists(), "Expected FHIRHelpers-4.0.1.cql to be materialized")
        val text = file!!.readText()
        assertTrue(text.contains("library FHIRHelpers"), "Expected real FHIRHelpers CQL content, got: $text")
    }

    @Test
    fun resolve_knownBundledVersion_prependsGeneratedHeaderAsCqlComment() {
        val file = BundledLibraryCache.resolve(id("FHIRHelpers", "4.0.1"), cacheRoot)!!
        val firstLine = file.readLines().first()

        assertTrue(firstLine.startsWith("//"), "Expected a leading CQL line comment, got: $firstLine")
        assertTrue(firstLine.contains("Do not edit"), "Expected a do-not-edit warning in the header")
    }

    @Test
    fun resolve_knownBundledVersion_marksFileReadOnly() {
        val file = BundledLibraryCache.resolve(id("FHIRHelpers", "4.0.1"), cacheRoot)!!

        assertFalse(file.canWrite(), "Expected the materialized file to be read-only")
    }

    @Test
    fun resolve_isIdempotent_doesNotRewriteExistingFile() {
        val first = BundledLibraryCache.resolve(id("FHIRHelpers", "4.0.1"), cacheRoot)!!
        val firstModified = first.lastModified()

        val second = BundledLibraryCache.resolve(id("FHIRHelpers", "4.0.1"), cacheRoot)!!

        assertEquals(first.canonicalPath, second.canonicalPath)
        assertEquals(firstModified, second.lastModified(), "Re-resolving must not rewrite the cached file")
    }

    // ── No match ───────────────────────────────────────────────────────────────

    @Test
    fun resolve_unknownLibrary_returnsNull() {
        val file = BundledLibraryCache.resolve(id("NotBundled", "1.0.0"), cacheRoot)

        assertNull(file)
        assertTrue(cacheRoot.listFiles()?.isEmpty() != false, "Must not create a cache dir/file on miss")
    }

    @Test
    fun resolve_unknownVersionOfKnownLibrary_returnsNull() {
        val file = BundledLibraryCache.resolve(id("FHIRHelpers", "999.0.0"), cacheRoot)

        assertNull(file)
    }

    @Test
    fun resolve_missingVersion_returnsNull() {
        // Mirrors FhirLibrarySourceProvider: bundled resolution requires an exact version.
        val file = BundledLibraryCache.resolve(idNoVersion("FHIRHelpers"), cacheRoot)

        assertNull(file)
    }

    // ── On-demand behavior ────────────────────────────────────────────────────

    @Test
    fun resolve_onMiss_doesNotExtractOtherBundledVersions() {
        BundledLibraryCache.resolve(id("FHIRHelpers", "4.0.1"), cacheRoot)

        val extractedFiles = cacheRoot.listFiles()?.map { it.name } ?: emptyList()
        assertEquals(listOf("FHIRHelpers-4.0.1.cql"), extractedFiles, "Only the requested version should be extracted")
    }
}
