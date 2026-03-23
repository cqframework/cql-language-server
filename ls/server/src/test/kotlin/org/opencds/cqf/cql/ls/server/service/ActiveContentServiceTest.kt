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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
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

    // -----------------------------------------------------------------------
    // read(URI)
    // -----------------------------------------------------------------------

    @Test
    fun read_uri_returnsNullForUnknownUri() {
        val svc = ActiveContentService()
        assertNull(svc.read(DOC_URI_PARSED))
    }

    @Test
    fun read_uri_returnsActiveContentAsStream() {
        val svc = openDoc(LIBRARY_CONTENT, 1)
        val content = svc.read(DOC_URI_PARSED)!!.readAllBytes().toString(Charsets.UTF_8)
        assertEquals(LIBRARY_CONTENT, content)
    }

    // -----------------------------------------------------------------------
    // read(root, identifier)
    // -----------------------------------------------------------------------

    @Test
    fun read_rootId_throwsWhenNoDocumentMatchesIdentifier() {
        val svc = ActiveContentService() // nothing open
        val id = VersionedIdentifier().withId("One").withVersion("1.0.0")
        assertThrows<IllegalStateException> { svc.read(ROOT, id) }
    }

    @Test
    fun read_rootId_returnsStreamForSingleMatchingDocument() {
        val svc = openDoc(LIBRARY_CONTENT, 1)
        val id = VersionedIdentifier().withId("One").withVersion("1.0.0")
        val content = svc.read(ROOT, id)!!.readAllBytes().toString(Charsets.UTF_8)
        assertEquals(LIBRARY_CONTENT, content)
    }

    @Test
    fun read_rootId_throwsWhenMultipleDocumentsMatch() {
        val svc = openDoc(LIBRARY_CONTENT, 1)
        // Open a second URI with the same library content so locate() returns two results
        val params2 = DidOpenTextDocumentParams()
        val item2 = TextDocumentItem()
        item2.uri = "file:///workspace/OneCopy.cql"
        item2.text = LIBRARY_CONTENT
        item2.version = 1
        params2.textDocument = item2
        svc.didOpen(DidOpenTextDocumentEvent(params2))

        val id = VersionedIdentifier().withId("One").withVersion("1.0.0")
        assertThrows<IllegalStateException> { svc.read(ROOT, id) }
    }

    // -----------------------------------------------------------------------
    // didChange — additional branches
    // -----------------------------------------------------------------------

    @Test
    fun didChange_rangeBased_appliesPatch() {
        val svc = openDoc("hello world", 1)

        val ce = TextDocumentContentChangeEvent()
        ce.range = Range(Position(0, 6), Position(0, 11))
        ce.text = "CQL"
        ce.rangeLength = 5

        val change = DidChangeTextDocumentParams()
        change.textDocument = VersionedTextDocumentIdentifier(DOC_URI, 2)
        change.contentChanges = listOf(ce)
        svc.didChange(DidChangeTextDocumentEvent(change))

        val content = svc.read(DOC_URI_PARSED)!!.readAllBytes().toString(Charsets.UTF_8)
        assertEquals("hello CQL", content)
    }

    @Test
    fun didChange_sameVersion_contentUnchanged() {
        val svc = openDoc("original", 5)

        // version == existing.version — not strictly greater, so change is ignored
        val change = DidChangeTextDocumentParams()
        change.textDocument = VersionedTextDocumentIdentifier(DOC_URI, 5)
        change.contentChanges = listOf(TextDocumentContentChangeEvent("should be ignored"))
        svc.didChange(DidChangeTextDocumentEvent(change))

        val content = svc.read(DOC_URI_PARSED)!!.readAllBytes().toString(Charsets.UTF_8)
        assertEquals("original", content)
    }

    @Test
    fun didChange_noopForUntrackedUri() {
        val svc = ActiveContentService() // nothing open
        val change = DidChangeTextDocumentParams()
        change.textDocument = VersionedTextDocumentIdentifier(DOC_URI, 1)
        change.contentChanges = listOf(TextDocumentContentChangeEvent("irrelevant"))
        assertDoesNotThrow { svc.didChange(DidChangeTextDocumentEvent(change)) }
        assertFalse(svc.activeUris().contains(DOC_URI_PARSED))
    }

    // -----------------------------------------------------------------------
    // Invalid URI handling — all three events must not throw
    // -----------------------------------------------------------------------

    @Test
    fun didOpen_malformedUri_doesNotCrash() {
        val svc = ActiveContentService()
        val params = DidOpenTextDocumentParams()
        val item = TextDocumentItem()
        item.uri = "not a valid uri with spaces"
        item.text = LIBRARY_CONTENT
        item.version = 1
        params.textDocument = item
        assertDoesNotThrow { svc.didOpen(DidOpenTextDocumentEvent(params)) }
        assertTrue(svc.activeUris().isEmpty())
    }

    @Test
    fun didClose_malformedUri_doesNotCrash() {
        val svc = openDoc(LIBRARY_CONTENT, 1)
        val close = DidCloseTextDocumentParams()
        close.textDocument = TextDocumentIdentifier("not a valid uri with spaces")
        assertDoesNotThrow { svc.didClose(DidCloseTextDocumentEvent(close)) }
        // original document still tracked
        assertTrue(svc.activeUris().contains(DOC_URI_PARSED))
    }

    @Test
    fun didChange_malformedUri_doesNotCrash() {
        val svc = ActiveContentService()
        val change = DidChangeTextDocumentParams()
        change.textDocument = VersionedTextDocumentIdentifier("not a valid uri with spaces", 1)
        change.contentChanges = listOf(TextDocumentContentChangeEvent("text"))
        assertDoesNotThrow { svc.didChange(DidChangeTextDocumentEvent(change)) }
    }

    // -----------------------------------------------------------------------
    // searchActiveContent — null version
    // -----------------------------------------------------------------------

    @Test
    fun searchActiveContent_nullVersion_doesNotMatchVersionedLibrary() {
        // When identifier.version == null the regex is: (?s).*library\s+{id}'\s+(?s).*
        // This requires a literal ' immediately after the library name, so it does NOT
        // match a standard "library One version '1.0.0'" declaration.
        val svc = openDoc(LIBRARY_CONTENT, 1)
        val id = VersionedIdentifier().withId("One") // no version
        val found = svc.searchActiveContent(ROOT, id)
        assertTrue(found.isEmpty(), "null-version search should not match a versioned library declaration")
    }

    // -----------------------------------------------------------------------
    // patch — additional cases
    // -----------------------------------------------------------------------

    @Test
    @Suppress("DEPRECATION")
    fun patch_deletion_removesSelectedRange() {
        val svc = ActiveContentService()

        val change = TextDocumentContentChangeEvent()
        change.range = Range(Position(0, 5), Position(0, 11)) // selects " world"
        change.text = ""
        change.rangeLength = 6

        val result = svc.patch("hello world", change)

        assertEquals("hello", result)
    }

    companion object {
        private const val DOC_URI = "file:///workspace/One.cql"
        private val DOC_URI_PARSED: URI = Uris.parseOrNull(DOC_URI)!!
        private val ROOT: URI = Uris.parseOrNull("file:///workspace/")!!
        private const val LIBRARY_CONTENT = "library One version '1.0.0'\n\ndefine \"Test\":\n  1\n"
    }
}
