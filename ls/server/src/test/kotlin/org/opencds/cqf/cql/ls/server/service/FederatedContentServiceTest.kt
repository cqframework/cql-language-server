package org.opencds.cqf.cql.ls.server.service

import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.opencds.cqf.cql.ls.core.ContentService
import java.io.ByteArrayInputStream
import java.net.URI

class FederatedContentServiceTest {
    private lateinit var activeService: ActiveContentService
    private lateinit var fileService: ContentService
    private lateinit var fedService: FederatedContentService

    @BeforeEach
    fun setUp() {
        activeService = mock(ActiveContentService::class.java)
        fileService = mock(ContentService::class.java)
        fedService = FederatedContentService(activeService, fileService)
    }

    // -----------------------------------------------------------------------
    // locate — merges results from both services
    // -----------------------------------------------------------------------

    @Test
    fun locate_mergesActiveAndFileResults() {
        val id = VersionedIdentifier().withId("One").withVersion("1.0.0")
        `when`(activeService.locate(ROOT, id)).thenReturn(mutableSetOf(ACTIVE_URI))
        `when`(fileService.locate(ROOT, id)).thenReturn(mutableSetOf(FILE_URI))

        val result = fedService.locate(ROOT, id)

        assertTrue(result.contains(ACTIVE_URI))
        assertTrue(result.contains(FILE_URI))
    }

    @Test
    fun locate_emptyFromBoth_returnsEmptySet() {
        val id = VersionedIdentifier().withId("Unknown")
        `when`(activeService.locate(ROOT, id)).thenReturn(mutableSetOf())
        `when`(fileService.locate(ROOT, id)).thenReturn(mutableSetOf())

        val result = fedService.locate(ROOT, id)

        assertTrue(result.isEmpty())
    }

    // -----------------------------------------------------------------------
    // read — prefers active content when URI is in active set
    // -----------------------------------------------------------------------

    @Test
    fun read_uriInActiveSet_returnsActiveStream() {
        val expected = ByteArrayInputStream("active content".toByteArray())
        `when`(activeService.activeUris()).thenReturn(setOf(ACTIVE_URI))
        `when`(activeService.read(ACTIVE_URI)).thenReturn(expected)

        val result = fedService.read(ACTIVE_URI)

        assertSame(expected, result)
    }

    @Test
    fun read_uriNotInActiveSet_fallsBackToFileService() {
        val expected = ByteArrayInputStream("file content".toByteArray())
        `when`(activeService.activeUris()).thenReturn(emptySet())
        `when`(fileService.read(FILE_URI)).thenReturn(expected)

        val result = fedService.read(FILE_URI)

        assertSame(expected, result)
    }

    companion object {
        private val ROOT: URI = URI.create("file:///workspace/")
        private val ACTIVE_URI: URI = URI.create("file:///workspace/One.cql")
        private val FILE_URI: URI = URI.create("file:///workspace/lib/One.cql")
    }
}
