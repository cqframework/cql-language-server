package org.opencds.cqf.cql.ls.server.manager

import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.writeString
import org.cqframework.cql.cql2elm.LibraryContentType
import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.LibrarySourceLoader
import org.cqframework.cql.cql2elm.LibrarySourceProvider
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

private class StubLibrarySourceLoader(
    private val idToText: Map<String, String>,
) : LibrarySourceLoader {
    override fun getLibrarySource(identifier: VersionedIdentifier): Source? =
        idToText[identifier.id]?.let { text -> Buffer().also { it.writeString(text) } }

    override fun getLibraryContent(
        identifier: VersionedIdentifier,
        type: LibraryContentType,
    ): Source? = null

    override fun clearProviders() {}

    override fun registerProvider(provider: LibrarySourceProvider) {}
}

class LibrarySourceResolverTest {
    @Test
    fun `resolves via the library manager's provider chain when it has the identifier`() {
        val identifier =
            VersionedIdentifier().also {
                it.id = "CQMCommon"
                it.version = "2.0.0"
            }
        val libraryManager = mock(LibraryManager::class.java)
        `when`(libraryManager.librarySourceLoader).thenReturn(
            StubLibrarySourceLoader(mapOf("CQMCommon" to "library CQMCommon version '2.0.0'")),
        )

        val result = LibrarySourceResolver.resolve(libraryManager, identifier)

        assertEquals("library CQMCommon version '2.0.0'", result)
    }

    @Test
    fun `normalizes a namespace-qualified id before falling back to the bundled provider`() {
        val identifier =
            VersionedIdentifier().also {
                it.id = "http://hl7.org/fhir/uv/cql/FHIRHelpers"
                it.version = "4.0.1"
            }

        val result = LibrarySourceResolver.resolve(null, identifier)

        assertTrue(result != null && result.contains("library FHIRHelpers"), "expected bundled FHIRHelpers source, got: $result")
    }

    @Test
    fun `returns null when neither the provider chain nor the bundled provider resolve the identifier`() {
        val identifier =
            VersionedIdentifier().also {
                it.id = "SomeLibraryThatDoesNotExist"
                it.version = "1.0.0"
            }

        val result = LibrarySourceResolver.resolve(null, identifier)

        assertNull(result)
    }

    @Test
    fun `swallows exceptions from a misbehaving library manager and falls through to the bundled provider`() {
        val identifier =
            VersionedIdentifier().also {
                it.id = "http://hl7.org/fhir/uv/cql/FHIRHelpers"
                it.version = "4.0.1"
            }
        val libraryManager = mock(LibraryManager::class.java)
        `when`(libraryManager.librarySourceLoader).thenThrow(RuntimeException("boom"))

        val result = LibrarySourceResolver.resolve(libraryManager, identifier)

        assertTrue(result != null && result.contains("library FHIRHelpers"), "expected bundled FHIRHelpers source, got: $result")
    }
}
