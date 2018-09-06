package org.cqframework.cql;

import com.google.common.base.Joiner;
import com.google.common.collect.Sets;
import org.cqframework.cql.cql2elm.CqlTranslatorException;
import org.cqframework.cql.cql2elm.LibrarySourceProvider;
import org.cqframework.cql.org.cqframework.cql.fhir.FhirTextDocumentProvider;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.hl7.elm.r1.VersionedIdentifier;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

class CqlTextDocumentService implements TextDocumentService {
    private static final Logger LOG = Logger.getLogger("main");

    // LibrarySourceProvider implementation that pulls from the active content in the language server,
    // or the TextDocumentProvider if the content is not active
    class CqlTextDocumentServiceLibrarySourceProvider implements LibrarySourceProvider {

        public CqlTextDocumentServiceLibrarySourceProvider() {
        }

        @Override
        public InputStream getLibrarySource(VersionedIdentifier versionedIdentifier) {
            // TODO: Resolve the use of versioned identifier versus a URI...
            URI documentUri = null;
            try {
                documentUri = new URI(server.getRootUri() + "/" + versionedIdentifier.getId());
            } catch (URISyntaxException e) {
                LOG.log(Level.SEVERE, e.getMessage());
                //e.printStackTrace();
            }

            Optional<String> content = activeContent(documentUri);
            if (content.isPresent()) {
                return new ByteArrayInputStream(content.get().getBytes(StandardCharsets.UTF_8));
            }

            TextDocumentItem textDocumentItem = textDocumentProvider.getDocument(documentUri.toString());
            if (textDocumentItem != null) {
                return new ByteArrayInputStream(textDocumentItem.getText().getBytes(StandardCharsets.UTF_8));
            }

            return null;
        }
    }

    private final CompletableFuture<LanguageClient> client;
    private final CqlLanguageServer server;
    private final TextDocumentProvider textDocumentProvider = new FhirTextDocumentProvider();
    private final Map<URI, VersionedContent> activeDocuments = new HashMap<>();
    private final Map<URI, TextDocumentItem> allDocuments = new HashMap<>();

    CqlTextDocumentService(CompletableFuture<LanguageClient> client, CqlLanguageServer server) {
        this.client = client;
        this.server = server;
    }

    LibrarySourceProvider getLibrarySourceProvider() {
        return new CqlTextDocumentServiceLibrarySourceProvider();
    }

    /** Text of file, if it is in the active set */
    Optional<String> activeContent(URI file) {
        return Optional.ofNullable(activeDocuments.get(file)).map(doc -> doc.content);
    }

    /** All open files, not including things like old git-versions in a diff view */
    Set<URI> openFiles() {
        return Sets.filter(activeDocuments.keySet(), uri -> uri.getScheme().equals("fhir"));
    }

    void loadDocuments(String rootUri) {
        for (TextDocumentItem item : textDocumentProvider.getDocuments(rootUri)) {
            try {
                allDocuments.put(new URI(item.getUri()), item);
            } catch (URISyntaxException e) {
                //e.printStackTrace();
                LOG.log(Level.SEVERE, e.getMessage());
            }
        }
    }

    void doLint(Collection<URI> paths) {
         LOG.info("Lint " + Joiner.on(", ").join(paths));

        for (URI uri : paths) {
            Optional<String> content = activeContent(uri);
            if (content.isPresent()) {
                List<CqlTranslatorException> exceptions = server.getTranslationManager().translate(content.get());

                LOG.info(String.format("lint completed on %s with %d messages.", uri, exceptions.size()));

                PublishDiagnosticsParams params = new PublishDiagnosticsParams(uri.toString(), CqlUtilities.convert(exceptions));
                for (Diagnostic diagnostic : params.getDiagnostics()) {
                    LOG.info(String.format("diagnostic: %s %d:%d-%d:%d: %s", uri, diagnostic.getRange().getStart().getLine(), diagnostic.getRange().getStart().getCharacter(),
                            diagnostic.getRange().getEnd().getLine(), diagnostic.getRange().getEnd().getCharacter(), diagnostic.getMessage()));
                }
                client.join().publishDiagnostics(params);
            }
        }
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
            TextDocumentPositionParams position) {

        URI uri = URI.create(position.getTextDocument().getUri());
        Optional<String> content = activeContent(uri);
        int line = position.getPosition().getLine() + 1;
        int character = position.getPosition().getCharacter() + 1;

        LOG.info(String.format("completion at %s %d:%d", uri, line, character));

        List<CompletionItem> items = new ArrayList<CompletionItem>();

        CompletionItem item = new CompletionItem();
        item.setKind(CompletionItemKind.Keyword);
        item.setLabel("declare");

        items.add(item);

        CompletionList list = new CompletionList(items);

        return CompletableFuture.completedFuture(Either.forRight(list));
    }

    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
        return CompletableFutures.computeAsync(
                cancel -> {
                    // server.configured().docs.resolveCompletionItem(unresolved);

                    return unresolved;
                });
    }

    @Override
    public CompletableFuture<Hover> hover(TextDocumentPositionParams position) {
        return null;
    }

    @Override
    public CompletableFuture<SignatureHelp> signatureHelp(TextDocumentPositionParams position) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends Location>> definition(
            TextDocumentPositionParams position) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(
            TextDocumentPositionParams position) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends SymbolInformation>> documentSymbol(
            DocumentSymbolParams params) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends Command>> codeAction(CodeActionParams params) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
        return null;
    }

    @Override
    public CompletableFuture<CodeLens> resolveCodeLens(CodeLens unresolved) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> rangeFormatting(
            DocumentRangeFormattingParams params) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(
            DocumentOnTypeFormattingParams params) {
        return null;
    }

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        return null;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        TextDocumentItem document = params.getTextDocument();
        URI uri = URI.create(document.getUri());

        activeDocuments.put(uri, new VersionedContent(document.getText(), document.getVersion()));

        doLint(Collections.singleton(uri));
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        VersionedTextDocumentIdentifier document = params.getTextDocument();
        URI uri = URI.create(document.getUri());
        VersionedContent existing = activeDocuments.get(uri);
        String newText = existing.content;

        if (document.getVersion() > existing.version) {
            for (TextDocumentContentChangeEvent change : params.getContentChanges()) {
                if (change.getRange() == null)
                    activeDocuments.put(
                            uri, new VersionedContent(change.getText(), document.getVersion()));
                else newText = patch(newText, change);
            }

            activeDocuments.put(uri, new VersionedContent(newText, document.getVersion()));
        } else
            LOG.warning(
                    "Ignored change with version "
                            + document.getVersion()
                            + " <= "
                            + existing.version);
    }

    private String patch(String sourceText, TextDocumentContentChangeEvent change) {
        try {
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
            writer.write(change.getText());

            // Skip replaced text
            reader.skip(change.getRangeLength());

            // Write remaining text
            while (true) {
                int next = reader.read();

                if (next == -1) return writer.toString();
                else writer.write(next);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        TextDocumentIdentifier document = params.getTextDocument();
        URI uri = URI.create(document.getUri());

        // Remove from source cache
        activeDocuments.remove(uri);

        // Clear diagnostics
        client.join()
                 .publishDiagnostics(
                         new PublishDiagnosticsParams(uri.toString(), new ArrayList<>()));
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        // Re-lint all active documents
        doLint(openFiles());
    }
}
