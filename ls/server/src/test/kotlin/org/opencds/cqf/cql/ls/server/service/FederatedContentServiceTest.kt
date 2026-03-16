package org.opencds.cqf.cql.ls.server.service

import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentItem
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.event.DidOpenTextDocumentEvent
import java.io.ByteArrayInputStream
import java.net.URI

class FederatedContentServiceTest {
    private lateinit var activeService: ActiveContentService
    private lateinit var fileService: ContentService
    private lateinit var fedService: FederatedContentService

    @BeforeEach
    fun setUp() {
        activeService = ActiveContentService()
        fileService = mock(ContentService::class.java)
        fedService = FederatedContentService(activeService, fileService)
    }

    private fun openDoc(
        uri: String,
        content: String,
    ) {
        val params = DidOpenTextDocumentParams()
        val item = TextDocumentItem()
        item.uri = uri
        item.text = content
        item.version = 1
        params.textDocument = item
        activeService.didOpen(DidOpenTextDocumentEvent(params))
    }

    // -----------------------------------------------------------------------
    // locate — merges results from both services
    // -----------------------------------------------------------------------

    @Test
    fun locate_mergesActiveAndFileResults() {
        openDoc(ACTIVE_URI.toString(), "library One version '1.0.0'\ndefine \"X\": 1")

        val id = VersionedIdentifier().withId("One").withVersion("1.0.0")
        `when`(fileService.locate(ROOT, id)).thenReturn(mutableSetOf(FILE_URI))

        val result = fedService.locate(ROOT, id)

        assertTrue(result.contains(ACTIVE_URI))
        assertTrue(result.contains(FILE_URI))
    }

    @Test
    fun locate_emptyFromBoth_returnsEmptySet() {
        val id = VersionedIdentifier().withId("Unknown")
        `when`(fileService.locate(ROOT, id)).thenReturn(mutableSetOf())

        val result = fedService.locate(ROOT, id)

        assertTrue(result.isEmpty())
    }

    // -----------------------------------------------------------------------
    // read — prefers active content when URI is in active set
    // -----------------------------------------------------------------------

    @Test
    fun read_uriInActiveSet_returnsActiveContent() {
        openDoc(ACTIVE_URI.toString(), "library One version '1.0.0'\ndefine \"X\": 1")

        val result = fedService.read(ACTIVE_URI)

        assertNotNull(result)
    }

    @Test
    fun read_uriNotInActiveSet_fallsBackToFileService() {
        val expected = ByteArrayInputStream("file content".toByteArray())
        `when`(fileService.read(FILE_URI)).thenReturn(expected)

        // FILE_URI was never opened in activeService, so it falls through to fileService
        val result = fedService.read(FILE_URI)

        assertNull(activeService.read(FILE_URI)) // confirm not in active set
        assertNotNull(result)
    }

    companion object {
        private val ROOT: URI = URI.create("file:///workspace/")
        private val ACTIVE_URI: URI = URI.create("file:///workspace/One.cql")
        private val FILE_URI: URI = URI.create("file:///workspace/lib/One.cql")
    }
}
