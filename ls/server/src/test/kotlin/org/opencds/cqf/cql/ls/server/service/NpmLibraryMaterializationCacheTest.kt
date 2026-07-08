package org.opencds.cqf.cql.ls.server.service

import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class NpmLibraryMaterializationCacheTest {
    @TempDir
    lateinit var cacheRoot: File

    private fun id(
        name: String,
        version: String,
        system: String,
    ) = VersionedIdentifier().withId(name).withVersion(version).withSystem(system)

    // ── materialize ────────────────────────────────────────────────────────────

    @Test
    fun materialize_writesFileWithGeneratedHeaderAndRealContent() {
        val identifier = id("FHIRHelpers", "4.0.1", "http://hl7.org/fhir/uv/cql")
        val cqlText = "library FHIRHelpers version '4.0.1'\ndefine function ToCode(x String): null"

        val file = NpmLibraryMaterializationCache.materialize(identifier, cqlText, cacheRoot)

        assertNotNull(file)
        val lines = file!!.readLines()
        assertTrue(lines.first().startsWith("//"), "Expected a leading CQL comment header")
        assertTrue(lines.first().contains("Do not edit"))
        assertTrue(file.readText().contains(cqlText))
    }

    @Test
    fun materialize_marksFileReadOnly() {
        val identifier = id("FHIRHelpers", "4.0.1", "http://hl7.org/fhir/uv/cql")

        val file = NpmLibraryMaterializationCache.materialize(identifier, "library FHIRHelpers version '4.0.1'", cacheRoot)!!

        assertFalse(file.canWrite())
    }

    @Test
    fun materialize_isIdempotent_doesNotOverwriteExistingFile() {
        val identifier = id("FHIRHelpers", "4.0.1", "http://hl7.org/fhir/uv/cql")
        val first = NpmLibraryMaterializationCache.materialize(identifier, "original content", cacheRoot)!!

        val second = NpmLibraryMaterializationCache.materialize(identifier, "different content", cacheRoot)!!

        assertEquals(first.canonicalPath, second.canonicalPath)
        assertTrue(second.readText().contains("original content"), "Must not overwrite an already-materialized file")
    }

    @Test
    fun materialize_missingVersionOrSystem_returnsNull() {
        val noVersion = VersionedIdentifier().withId("FHIRHelpers").withSystem("http://hl7.org/fhir/uv/cql")
        val noSystem = VersionedIdentifier().withId("FHIRHelpers").withVersion("4.0.1")

        assertNull(NpmLibraryMaterializationCache.materialize(noVersion, "text", cacheRoot))
        assertNull(NpmLibraryMaterializationCache.materialize(noSystem, "text", cacheRoot))
    }

    // ── lookup ─────────────────────────────────────────────────────────────────

    @Test
    fun lookup_beforeMaterialize_returnsNull() {
        val identifier = id("FHIRHelpers", "4.0.1", "http://hl7.org/fhir/uv/cql")

        assertNull(NpmLibraryMaterializationCache.lookup(identifier, cacheRoot))
    }

    @Test
    fun lookup_afterMaterialize_returnsSameFile() {
        val identifier = id("FHIRHelpers", "4.0.1", "http://hl7.org/fhir/uv/cql")
        val materialized = NpmLibraryMaterializationCache.materialize(identifier, "library FHIRHelpers version '4.0.1'", cacheRoot)!!

        val found = NpmLibraryMaterializationCache.lookup(identifier, cacheRoot)

        assertNotNull(found)
        assertEquals(materialized.canonicalPath, found!!.canonicalPath)
    }

    // ── namespace collisions ───────────────────────────────────────────────────

    @Test
    fun materialize_sameIdAndVersion_differentSystem_doesNotCollide() {
        val a = id("FHIRHelpers", "4.0.1", "http://hl7.org/fhir/uv/cql")
        val b = id("FHIRHelpers", "4.0.1", "http://example.com/other-package")

        val fileA = NpmLibraryMaterializationCache.materialize(a, "content A", cacheRoot)!!
        val fileB = NpmLibraryMaterializationCache.materialize(b, "content B", cacheRoot)!!

        assertNotEquals(fileA.canonicalPath, fileB.canonicalPath)
        assertTrue(fileA.readText().contains("content A"))
        assertTrue(fileB.readText().contains("content B"))
    }
}
