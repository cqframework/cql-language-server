package org.opencds.cqf.cql.ls.server.service;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.greenrobot.eventbus.Subscribe;
import org.hl7.elm.r1.VersionedIdentifier;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.server.ActiveContent;
import org.opencds.cqf.cql.ls.server.VersionedContent;
import org.opencds.cqf.cql.ls.server.event.DidChangeTextDocumentEvent;
import org.opencds.cqf.cql.ls.server.event.DidCloseTextDocumentEvent;
import org.opencds.cqf.cql.ls.server.event.DidOpenTextDocumentEvent;

public class ActiveContentService implements ContentService {

    private ContentService inactiveContentService;

    private ActiveContent activeContent = new ActiveContent();

    public ActiveContentService(ContentService inactiveContentService) {
        this.inactiveContentService = inactiveContentService;
    }

    @Override
    public List<URI> locate(VersionedIdentifier libraryIdentifier) {
        checkNotNull(libraryIdentifier);

        List<URI> uris = searchActiveContent(libraryIdentifier);

        uris.addAll(this.inactiveContentService.locate(libraryIdentifier));

        return uris;
    }

    @Override
    public InputStream read(VersionedIdentifier identifier) {
        List<URI> uris = this.locate(identifier);
        if (uris.isEmpty()) {
            return this.inactiveContentService.read(identifier);
        }

        checkState(uris.size() == 1, "Found more than one file for identifier: {}", identifier);

        return this.read(uris.get(0));
    }

    @Override
    public InputStream read(URI uri) {
        if (!this.activeContent.containsKey(uri)) {
            return this.inactiveContentService.read(uri);
        }

        String content = this.activeContent.get(uri).content;
        return new ByteArrayInputStream(content.getBytes());
    }

    @Subscribe
    public void didOpen(DidOpenTextDocumentEvent e) {
        TextDocumentItem document = e.params().getTextDocument();
        URI uri = URI.create(document.getUri());

        String encodedText = new String(document.getText().getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        activeContent.put(uri, new VersionedContent(encodedText, document.getVersion()));
    }

    @Subscribe
    public void didClose(DidCloseTextDocumentEvent e) {
        TextDocumentIdentifier document = e.params().getTextDocument();
        URI uri = URI.create(document.getUri());
        activeContent.remove(uri);
    }

    @Subscribe
    public void didChange(DidChangeTextDocumentEvent e) throws IOException {
        VersionedTextDocumentIdentifier document = e.params().getTextDocument();
        URI uri = URI.create(document.getUri());

        VersionedContent existing = activeContent.get(uri);
        String existingText = existing.content;

        if (document.getVersion() > existing.version) {
            for (TextDocumentContentChangeEvent change : e.params().getContentChanges()) {
                if (change.getRange() == null) {
                    String encodedText =
                            new String(change.getText().getBytes(StandardCharsets.UTF_8),
                                    StandardCharsets.UTF_8);
                    activeContent.put(uri,
                            new VersionedContent(encodedText, document.getVersion()));
                } else {
                    String newText = patch(existingText, change);
                    activeContent.put(uri, new VersionedContent(newText, document.getVersion()));

                }
            }
        }
    }

    // Break this out into its own thing for test purposes.
    @SuppressWarnings("deprecation")
    private String patch(String sourceText, TextDocumentContentChangeEvent change)
            throws IOException {
        Range range = change.getRange();
        BufferedReader reader = new BufferedReader(new StringReader(sourceText));
        StringWriter writer = new StringWriter();

        // Skip unchanged lines
        int line = 0;

        while (line < range.getStart().getLine()) {
            writer.write(reader.readLine() + '\n');
            line++;
        }

        // Skip unchanged chars
        for (int character = 0; character < range.getStart().getCharacter(); character++)
            writer.write(reader.read());

        // Write replacement text
        String encodedText = new String(change.getText().getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        writer.write(encodedText);

        // Skip replaced text
        reader.skip(change.getRangeLength());

        // Write remaining text
        while (true) {
            int next = reader.read();

            if (next == -1)
                return writer.toString();
            else
                writer.write(next);
        }
    }

    public List<URI> searchActiveContent(VersionedIdentifier identifier) {
        String id = identifier.getId();
        String version = identifier.getVersion();

        String matchText = "(?s).*library\\s+" + id;
        if (version != null) {
            matchText += ("\\s+version\\s+'" + version + "'\\s+(?s).*");
        } else {
            matchText += "'\\s+(?s).*";
        }

        List<URI> uris = new ArrayList<>();

        for (Entry<URI, VersionedContent> uri : this.activeContent.entrySet()) {
            String content = uri.getValue().content;
            // This will match if the content contains the library definition is present.
            if (content.matches(matchText)) {
                uris.add(uri.getKey());
            }
        }

        return uris;
    }
}
