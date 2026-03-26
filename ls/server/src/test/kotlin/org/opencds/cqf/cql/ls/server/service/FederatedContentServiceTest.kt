package org.opencds.cqf.cql.ls.server.service

import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentItem
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertNotNull
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
        uriString: String,
        content: String,
    ) {
        val params = DidOpenTextDocumentParams()
        val item = TextDocumentItem()
        item.uri = uriString
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
        openDoc("file:///workspace/One.cql", "library One version '1.0.0'\ndefine \"X\": 1")

        // Retrieve the URI exactly as stored — Uris.parseOrNull normalizes differently per platform
        val storedUri = activeService.activeUris().first()
        val fileOnlyUri = URI.create("file:///other/lib/One.cql")

        val id = VersionedIdentifier().withId("One").withVersion("1.0.0")
        `when`(fileService.locate(storedUri, id)).thenReturn(setOf(fileOnlyUri))

        val result = fedService.locate(storedUri, id)

        assertTrue(result.contains(storedUri))
        assertTrue(result.contains(fileOnlyUri))
    }

    @Test
    fun locate_emptyFromBoth_returnsEmptySet() {
        val root = URI.create("file:///workspace/")
        val id = VersionedIdentifier().withId("Unknown")
        `when`(fileService.locate(root, id)).thenReturn(emptySet())

        val result = fedService.locate(root, id)

        assertTrue(result.isEmpty())
    }

    // -----------------------------------------------------------------------
    // read — prefers active content when URI is in active set
    // -----------------------------------------------------------------------

    @Test
    fun read_uriInActiveSet_returnsActiveContent() {
        openDoc("file:///workspace/One.cql", "library One version '1.0.0'\ndefine \"X\": 1")

        // Use the actual stored URI to avoid platform-specific normalization differences
        val storedUri = activeService.activeUris().first()
        val result = fedService.read(storedUri)

        assertNotNull(result)
    }

    @Test
    fun read_uriNotInActiveSet_fallsBackToFileService() {
        val fileUri = URI.create("file:///workspace/lib/One.cql")
        val expected = ByteArrayInputStream("file content".toByteArray())
        `when`(fileService.read(fileUri)).thenReturn(expected)

        // fileUri was never opened in activeService, so fedService falls through to fileService
        val result = fedService.read(fileUri)

        assertNotNull(result)
    }
}
