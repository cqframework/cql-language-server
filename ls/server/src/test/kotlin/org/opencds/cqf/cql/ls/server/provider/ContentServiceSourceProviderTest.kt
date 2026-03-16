package org.opencds.cqf.cql.ls.server.provider

import kotlinx.io.Source
import org.cqframework.cql.cql2elm.LibrarySourceProvider
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.service.TestContentService
import java.net.URI

class ContentServiceSourceProviderTest {
    @Test
    fun getLibrarySource_knownLibrary_returnsSource() {
        val id = VersionedIdentifier().withId("One")
        val source: Source? = provider.getLibrarySource(id)
        assertNotNull(source)
    }

    @Test
    fun getLibrarySource_unknownLibrary_returnsNull() {
        val id = VersionedIdentifier().withId("DoesNotExistLibrary")
        val source: Source? = provider.getLibrarySource(id)
        assertNull(source)
    }

    @Test
    fun getLibrarySource_implementsLibrarySourceProvider() {
        assertNotNull(provider as LibrarySourceProvider)
    }

    companion object {
        private lateinit var provider: ContentServiceSourceProvider

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val cs: ContentService = TestContentService()
            val root = URI.create("file:///workspace/")
            provider = ContentServiceSourceProvider(root, cs)
        }
    }
}
