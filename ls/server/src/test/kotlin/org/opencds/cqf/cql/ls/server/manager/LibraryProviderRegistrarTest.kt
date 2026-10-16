package org.opencds.cqf.cql.ls.server.manager

import kotlinx.io.Source
import org.cqframework.cql.cql2elm.LibraryContentType
import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.LibrarySourceLoader
import org.cqframework.cql.cql2elm.LibrarySourceProvider
import org.cqframework.cql.cql2elm.quick.FhirLibrarySourceProvider
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

private class RecordingLibrarySourceLoader : LibrarySourceLoader {
    val registered = mutableListOf<LibrarySourceProvider>()

    override fun getLibrarySource(identifier: VersionedIdentifier): Source? = null

    override fun getLibraryContent(
        identifier: VersionedIdentifier,
        type: LibraryContentType,
    ): Source? = null

    override fun clearProviders() = registered.clear()

    override fun registerProvider(provider: LibrarySourceProvider) {
        registered.add(provider)
    }
}

class LibraryProviderRegistrarTest {
    @Test
    fun `registers workspace namespaces then the bundled FhirLibrarySourceProvider last`() {
        val librarySourceLoader = RecordingLibrarySourceLoader()
        val libraryManager = mock(LibraryManager::class.java)
        `when`(libraryManager.librarySourceLoader).thenReturn(librarySourceLoader)
        val libraryResolutionManager = mock(LibraryResolutionManager::class.java)

        registerCommonLibraryProviders(libraryManager, libraryResolutionManager)

        verify(libraryResolutionManager).registerWorkspaceNamespaces(libraryManager)
        assertEquals(1, librarySourceLoader.registered.size)
        assertTrue(librarySourceLoader.registered[0] is FhirLibrarySourceProvider)
    }
}
