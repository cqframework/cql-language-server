package org.opencds.cqf.cql.ls.server.manager

import org.cqframework.cql.cql2elm.LibraryManager
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.opencds.cqf.cql.ls.server.service.TestContentService

class VirtualLibraryUrisTest {
    @Test
    fun `of gives versioned URI`() {
        val id =
            VersionedIdentifier().apply {
                id = "FHIRHelpers"
                version = "4.0.1"
            }
        val uri = VirtualLibraryUris.of(id)
        assertEquals("cql-virtual:///FHIRHelpers-4.0.1.cql", uri.toString())
    }

    @Test
    fun `of gives unversioned URI`() {
        val id =
            VersionedIdentifier().apply {
                id = "FHIRCommon"
            }
        val uri = VirtualLibraryUris.of(id)
        assertEquals("cql-virtual:///FHIRCommon-unversioned.cql", uri.toString())
    }

    @Test
    fun `of strips namespace qualified id`() {
        val id =
            VersionedIdentifier().apply {
                id = "hl7.fhir.uv.cql/FHIRCommon"
                version = "2.0.0"
            }
        val uri = VirtualLibraryUris.of(id)
        assertEquals("cql-virtual:///FHIRCommon-2.0.0.cql", uri.toString())
    }

    @Test
    fun `of returns null when id is null`() {
        val id = VersionedIdentifier()
        assertNull(VirtualLibraryUris.of(id))
    }

    @Test
    fun `parse treats triple slash and single slash forms as equal`() {
        val uri1 = VirtualLibraryUris.parse("cql-virtual:///X.cql")
        val uri2 = VirtualLibraryUris.parse("cql-virtual:/X.cql")
        assertNotNull(uri1)
        assertNotNull(uri2)
        assertEquals(uri1, uri2)
        assertEquals(uri1.hashCode(), uri2.hashCode())
        assertEquals("cql-virtual:///X.cql", uri1.toString())
    }

    @Test
    fun `parse returns null for non virtual URIs`() {
        assertNull(VirtualLibraryUris.parse("file:///x.cql"))
        assertNull(VirtualLibraryUris.parse("http://example.com/x.cql"))
        assertNull(VirtualLibraryUris.parse("/a/b.cql"))
    }

    @Test
    fun `looksLikeUri returns correct values`() {
        assertTrue(VirtualLibraryUris.looksLikeUri("cql-virtual:/x"))
        assertTrue(VirtualLibraryUris.looksLikeUri("file:///x"))
        assertFalse(VirtualLibraryUris.looksLikeUri("/a/b.cql"))
        assertFalse(VirtualLibraryUris.looksLikeUri("C:\\a\\b.cql"))
        assertFalse(VirtualLibraryUris.looksLikeUri("C:/a/b.cql"))
        assertFalse(VirtualLibraryUris.looksLikeUri("c:/a/b.cql"))
        assertFalse(VirtualLibraryUris.looksLikeUri("relative/b.cql"))
        assertFalse(VirtualLibraryUris.looksLikeUri(null))
    }

    @Test
    fun `ensureCompiled compiles once`() {
        val cs = TestContentService()
        val cm = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
        val id =
            VersionedIdentifier().apply {
                id = "FHIRHelpers"
                version = "4.0.1"
            }
        val uri = VirtualLibraryUris.ensureCompiled(cm, null, id)
        assertNotNull(uri)
        val firstText = cm.getSourceText(uri!!)
        val uri2 = VirtualLibraryUris.ensureCompiled(cm, null, id)
        assertEquals(uri, uri2)
        val secondText = cm.getSourceText(uri2!!)
        assertEquals(firstText, secondText)
    }

    @Test
    fun `ensureCompiled returns null when resolver returns null`() {
        val cs = TestContentService()
        // No provider chain, and not a bundled library, so nothing can resolve the source.
        val lm = mock(LibraryManager::class.java)
        `when`(lm.librarySourceLoader).thenReturn(null)
        val cm = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
        val id =
            VersionedIdentifier().apply {
                id = "NonExistent"
                version = "1.0.0"
            }
        val uri = VirtualLibraryUris.ensureCompiled(cm, lm, id)
        assertNull(uri)
    }

    @Test
    fun `ensureCompiled returns null for identifier with null id`() {
        val cs = TestContentService()
        val cm = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
        val id = VersionedIdentifier()
        assertNull(VirtualLibraryUris.ensureCompiled(cm, null, id))
    }

    @Test
    fun `ensureCompiled cache hit skips resolver`() {
        val cs = TestContentService()
        val cm = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
        val id =
            VersionedIdentifier().apply {
                id = "FHIRHelpers"
                version = "4.0.1"
            }
        val uri = VirtualLibraryUris.ensureCompiled(cm, null, id)
        assertNotNull(uri)
        // A second call must hit the cache and never consult the library manager's provider chain.
        val lm = mock(LibraryManager::class.java)
        assertEquals(uri, VirtualLibraryUris.ensureCompiled(cm, lm, id))
        verify(lm, never()).librarySourceLoader
    }
}
