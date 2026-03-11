package org.opencds.cqf.cql.ls.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Collections;
import java.util.Set;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.hl7.elm.r1.VersionedIdentifier;
import org.junit.jupiter.api.Test;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.event.DidChangeTextDocumentEvent;
import org.opencds.cqf.cql.ls.server.event.DidCloseTextDocumentEvent;
import org.opencds.cqf.cql.ls.server.event.DidOpenTextDocumentEvent;

class ActiveContentServiceTest {

    private static final String DOC_URI = "file:///workspace/One.cql";
    private static final URI DOC_URI_PARSED = Uris.parseOrNull(DOC_URI);
    private static final URI ROOT = Uris.parseOrNull("file:///workspace/");

    private static final String LIBRARY_CONTENT = "library One version '1.0.0'\n\ndefine \"Test\":\n  1\n";

    private ActiveContentService openDoc(String text, int version) throws Exception {
        ActiveContentService svc = new ActiveContentService();
        DidOpenTextDocumentParams params = new DidOpenTextDocumentParams();
        TextDocumentItem item = new TextDocumentItem();
        item.setUri(DOC_URI);
        item.setText(text);
        item.setVersion(version);
        params.setTextDocument(item);
        svc.didOpen(new DidOpenTextDocumentEvent(params));
        return svc;
    }

    // -----------------------------------------------------------------------
    // didOpen / didClose
    // -----------------------------------------------------------------------

    @Test
    void didOpen_addsUriToActiveSet() throws Exception {
        ActiveContentService svc = openDoc(LIBRARY_CONTENT, 1);
        assertTrue(svc.activeUris().contains(DOC_URI_PARSED));
    }

    @Test
    void didClose_removesUriFromActiveSet() throws Exception {
        ActiveContentService svc = openDoc(LIBRARY_CONTENT, 1);

        DidCloseTextDocumentParams close = new DidCloseTextDocumentParams();
        TextDocumentIdentifier docId = new TextDocumentIdentifier(DOC_URI);
        close.setTextDocument(docId);
        svc.didClose(new DidCloseTextDocumentEvent(close));

        assertFalse(svc.activeUris().contains(DOC_URI_PARSED));
    }

    // -----------------------------------------------------------------------
    // didChange
    // -----------------------------------------------------------------------

    @Test
    void didChange_fullReplacement_updatesContent() throws Exception {
        ActiveContentService svc = openDoc("old content", 1);

        DidChangeTextDocumentParams change = new DidChangeTextDocumentParams();
        VersionedTextDocumentIdentifier vid = new VersionedTextDocumentIdentifier(DOC_URI, 2);
        change.setTextDocument(vid);
        TextDocumentContentChangeEvent ev = new TextDocumentContentChangeEvent("new content");
        change.setContentChanges(Collections.singletonList(ev));

        svc.didChange(new DidChangeTextDocumentEvent(change));

        // Read the updated content back
        String updated = new String(svc.read(DOC_URI_PARSED).readAllBytes());
        assertEquals("new content", updated);
    }

    @Test
    void didChange_olderVersion_contentUnchanged() throws Exception {
        ActiveContentService svc = openDoc("original", 5);

        DidChangeTextDocumentParams change = new DidChangeTextDocumentParams();
        // version 3 < current version 5, so change should be ignored
        VersionedTextDocumentIdentifier vid = new VersionedTextDocumentIdentifier(DOC_URI, 3);
        change.setTextDocument(vid);
        TextDocumentContentChangeEvent ev = new TextDocumentContentChangeEvent("should be ignored");
        change.setContentChanges(Collections.singletonList(ev));

        svc.didChange(new DidChangeTextDocumentEvent(change));

        String content = new String(svc.read(DOC_URI_PARSED).readAllBytes());
        assertEquals("original", content);
    }

    // -----------------------------------------------------------------------
    // patch
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("deprecation")
    void patch_singleLineReplacement_replacesCorrectRange() throws Exception {
        ActiveContentService svc = new ActiveContentService();

        TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent();
        change.setRange(new Range(new Position(0, 6), new Position(0, 11)));
        change.setText("CQL");
        change.setRangeLength(5);

        String result = svc.patch("hello world", change);

        assertEquals("hello CQL", result);
    }

    @Test
    @SuppressWarnings("deprecation")
    void patch_multiLineReplacement_replacesAcrossLines() throws Exception {
        ActiveContentService svc = new ActiveContentService();

        // Source: "line1\nline2\nline3"
        // Replace from (0,5) to end of "line2": replacement = " and "
        // rangeLength = 6 chars ("\nline2")
        TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent();
        change.setRange(new Range(new Position(0, 5), new Position(1, 5)));
        change.setText(" and ");
        change.setRangeLength(6);

        String result = svc.patch("line1\nline2\nline3", change);

        assertEquals("line1 and \nline3", result);
    }

    // -----------------------------------------------------------------------
    // searchActiveContent
    // -----------------------------------------------------------------------

    @Test
    void searchActiveContent_matchesNameAndVersion() throws Exception {
        ActiveContentService svc = openDoc(LIBRARY_CONTENT, 1);

        VersionedIdentifier id = new VersionedIdentifier().withId("One").withVersion("1.0.0");
        Set<URI> found = svc.searchActiveContent(ROOT, id);

        assertTrue(found.contains(DOC_URI_PARSED));
    }

    @Test
    void searchActiveContent_uriOutsideRoot_excluded() throws Exception {
        // The document URI is under /other/, not under ROOT (/workspace/).
        String otherUri = "file:///other/One.cql";
        ActiveContentService svc = new ActiveContentService();
        DidOpenTextDocumentParams params = new DidOpenTextDocumentParams();
        TextDocumentItem item = new TextDocumentItem();
        item.setUri(otherUri);
        item.setText(LIBRARY_CONTENT);
        item.setVersion(1);
        params.setTextDocument(item);
        svc.didOpen(new DidOpenTextDocumentEvent(params));

        VersionedIdentifier id = new VersionedIdentifier().withId("One").withVersion("1.0.0");
        Set<URI> found = svc.searchActiveContent(ROOT, id);

        assertFalse(found.contains(Uris.parseOrNull(otherUri)));
    }

    @Test
    void searchActiveContent_contentDoesNotMatch_notIncluded() throws Exception {
        ActiveContentService svc = openDoc("library Two version '2.0.0'\n\ndefine \"X\": 1", 1);

        // Searching for One 1.0.0 when doc contains Two 2.0.0 → no match
        VersionedIdentifier id = new VersionedIdentifier().withId("One").withVersion("1.0.0");
        Set<URI> found = svc.searchActiveContent(ROOT, id);

        assertNotNull(found);
        assertFalse(found.contains(DOC_URI_PARSED));
    }
}
