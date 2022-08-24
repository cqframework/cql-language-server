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
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.greenrobot.eventbus.Subscribe;
import org.hl7.elm.r1.VersionedIdentifier;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.event.DidChangeTextDocumentEvent;
import org.opencds.cqf.cql.ls.server.event.DidCloseTextDocumentEvent;
import org.opencds.cqf.cql.ls.server.event.DidOpenTextDocumentEvent;

public class ActiveContentService implements ContentService {
    public static class VersionedContent {
        public final String content;
        public final int version;

        public VersionedContent(String content, int version) {
            this.content = content;
            this.version = version;
        }
    }

    private final Map<URI, VersionedContent> activeContent = new ConcurrentHashMap<>();

    @Override
    public Set<URI> locate(URI root, VersionedIdentifier libraryIdentifier) {
        checkNotNull(root);
        checkNotNull(libraryIdentifier);

        return searchActiveContent(root, libraryIdentifier);
    }

    @Override
    public InputStream read(URI root, VersionedIdentifier identifier) {
        checkNotNull(root);
        checkNotNull(identifier);

        Set<URI> uris = this.locate(root, identifier);

        checkState(uris.size() == 1, "Found more than one file for identifier: {}", identifier);

        return this.read(uris.iterator().next());
    }

    @Override
    public InputStream read(URI uri) {
        checkNotNull(uri);

        String content = this.activeContent.get(uri).content;
        return new ByteArrayInputStream(content.getBytes());
    }

    @Subscribe(priority = 100)
    public void didOpen(DidOpenTextDocumentEvent e) {
        TextDocumentItem document = e.params().getTextDocument();
        URI uri = Uris.parseOrNull(document.getUri());

        String encodedText = new String(document.getText().getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        activeContent.put(uri, new VersionedContent(encodedText, document.getVersion()));
    }

    @Subscribe(priority = 100)
    public void didClose(DidCloseTextDocumentEvent e) {
        TextDocumentIdentifier document = e.params().getTextDocument();
        URI uri = Uris.parseOrNull(document.getUri());
        activeContent.remove(uri);
    }

    @Subscribe(priority = 100)
    public void didChange(DidChangeTextDocumentEvent e) throws IOException {
        VersionedTextDocumentIdentifier document = e.params().getTextDocument();
        URI uri = Uris.parseOrNull(document.getUri());

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
    protected String patch(String sourceText, TextDocumentContentChangeEvent change)
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

    protected Set<URI> searchActiveContent(URI root, VersionedIdentifier identifier) {
        String id = identifier.getId();
        String version = identifier.getVersion();

        String matchText = "(?s).*library\\s+" + id;
        if (version != null) {
            matchText += ("\\s+version\\s+'" + version + "'\\s+(?s).*");
        } else {
            matchText += "'\\s+(?s).*";
        }

        Set<URI> uris = new HashSet<>();

        for (Entry<URI, VersionedContent> entry : this.activeContent.entrySet()) {
            URI uri = entry.getKey();
            // Checks to see if the current entry is a child of the root URI.
            if (root.relativize(uri).equals(uri)) {
                continue;
            }

            String content = entry.getValue().content;
            // This will match if the content contains the library definition is present.
            if (content.matches(matchText)) {
                uris.add(entry.getKey());
            }
        }

        return uris;
    }

    public Set<URI> activeUris() {
        return this.activeContent.keySet();
    }
}
