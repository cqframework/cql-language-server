package org.opencds.cqf.cql.ls.server.service

import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.event.DidChangeTextDocumentEvent
import org.opencds.cqf.cql.ls.server.event.DidCloseTextDocumentEvent
import org.opencds.cqf.cql.ls.server.event.DidOpenTextDocumentEvent
import java.net.URI

class ActiveContentServiceTest {
    private fun openDoc(
        text: String,
        version: Int,
    ): ActiveContentService {
        val svc = ActiveContentService()
        val params = DidOpenTextDocumentParams()
        val item = TextDocumentItem()
        item.uri = DOC_URI
        item.text = text
        item.version = version
        params.textDocument = item
        svc.didOpen(DidOpenTextDocumentEvent(params))
        return svc
    }

    // -----------------------------------------------------------------------
    // didOpen / didClose
    // -----------------------------------------------------------------------

    @Test
    fun didOpen_addsUriToActiveSet() {
        val svc = openDoc(LIBRARY_CONTENT, 1)
        assertTrue(svc.activeUris().contains(DOC_URI_PARSED))
    }

    @Test
    fun didClose_removesUriFromActiveSet() {
        val svc = openDoc(LIBRARY_CONTENT, 1)

        val close = DidCloseTextDocumentParams()
        close.textDocument = TextDocumentIdentifier(DOC_URI)
        svc.didClose(DidCloseTextDocumentEvent(close))

        assertFalse(svc.activeUris().contains(DOC_URI_PARSED))
    }

    // -----------------------------------------------------------------------
    // didChange
    // -----------------------------------------------------------------------

    @Test
    fun didChange_fullReplacement_updatesContent() {
        val svc = openDoc("old content", 1)

        val change = DidChangeTextDocumentParams()
        change.textDocument = VersionedTextDocumentIdentifier(DOC_URI, 2)
        change.contentChanges = listOf(TextDocumentContentChangeEvent("new content"))
        svc.didChange(DidChangeTextDocumentEvent(change))

        val updated = svc.read(DOC_URI_PARSED)!!.readAllBytes().toString(Charsets.UTF_8)
        assertEquals("new content", updated)
    }

    @Test
    fun didChange_olderVersion_contentUnchanged() {
        val svc = openDoc("original", 5)

        val change = DidChangeTextDocumentParams()
        // version 3 < current version 5, so change should be ignored
        change.textDocument = VersionedTextDocumentIdentifier(DOC_URI, 3)
        change.contentChanges = listOf(TextDocumentContentChangeEvent("should be ignored"))
        svc.didChange(DidChangeTextDocumentEvent(change))

        val content = svc.read(DOC_URI_PARSED)!!.readAllBytes().toString(Charsets.UTF_8)
        assertEquals("original", content)
    }

    // -----------------------------------------------------------------------
    // patch
    // -----------------------------------------------------------------------

    @Test
    @Suppress("DEPRECATION")
    fun patch_singleLineReplacement_replacesCorrectRange() {
        val svc = ActiveContentService()

        val change = TextDocumentContentChangeEvent()
        change.range = Range(Position(0, 6), Position(0, 11))
        change.text = "CQL"
        change.rangeLength = 5

        val result = svc.patch("hello world", change)

        assertEquals("hello CQL", result)
    }

    @Test
    @Suppress("DEPRECATION")
    fun patch_multiLineReplacement_replacesAcrossLines() {
        val svc = ActiveContentService()

        // Source: "line1\nline2\nline3"
        // Replace from (0,5) to end of "line2": replacement = " and "
        // rangeLength = 6 chars ("\nline2")
        val change = TextDocumentContentChangeEvent()
        change.range = Range(Position(0, 5), Position(1, 5))
        change.text = " and "
        change.rangeLength = 6

        val result = svc.patch("line1\nline2\nline3", change)

        assertEquals("line1 and \nline3", result)
    }

    // -----------------------------------------------------------------------
    // searchActiveContent
    // -----------------------------------------------------------------------

    @Test
    fun searchActiveContent_matchesNameAndVersion() {
        val svc = openDoc(LIBRARY_CONTENT, 1)

        val id = VersionedIdentifier().withId("One").withVersion("1.0.0")
        val found = svc.searchActiveContent(ROOT, id)

        assertTrue(found.contains(DOC_URI_PARSED))
    }

    @Test
    fun searchActiveContent_uriOutsideRoot_excluded() {
        val otherUri = "file:///other/One.cql"
        val svc = ActiveContentService()
        val params = DidOpenTextDocumentParams()
        val item = TextDocumentItem()
        item.uri = otherUri
        item.text = LIBRARY_CONTENT
        item.version = 1
        params.textDocument = item
        svc.didOpen(DidOpenTextDocumentEvent(params))

        val id = VersionedIdentifier().withId("One").withVersion("1.0.0")
        val found = svc.searchActiveContent(ROOT, id)

        assertFalse(found.contains(Uris.parseOrNull(otherUri)))
    }

    @Test
    fun searchActiveContent_contentDoesNotMatch_notIncluded() {
        val svc = openDoc("library Two version '2.0.0'\n\ndefine \"X\": 1", 1)

        val id = VersionedIdentifier().withId("One").withVersion("1.0.0")
        val found = svc.searchActiveContent(ROOT, id)

        assertNotNull(found)
        assertFalse(found.contains(DOC_URI_PARSED))
    }

    companion object {
        private const val DOC_URI = "file:///workspace/One.cql"
        private val DOC_URI_PARSED: URI = Uris.parseOrNull(DOC_URI)!!
        private val ROOT: URI = Uris.parseOrNull("file:///workspace/")!!
        private const val LIBRARY_CONTENT = "library One version '1.0.0'\n\ndefine \"Test\":\n  1\n"
    }
}
