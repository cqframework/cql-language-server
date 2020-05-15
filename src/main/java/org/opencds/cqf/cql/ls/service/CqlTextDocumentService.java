package org.opencds.cqf.cql.ls.service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import com.google.common.base.Joiner;
import com.google.common.collect.Sets;

import org.apache.commons.lang3.tuple.Pair;
import org.cqframework.cql.cql2elm.CqlTranslator;
import org.cqframework.cql.cql2elm.CqlTranslatorException;
import org.cqframework.cql.elm.tracking.TrackBack;
import org.cqframework.cql.tools.formatter.CqlFormatterVisitor;
import org.cqframework.cql.tools.formatter.CqlFormatterVisitor.FormatResult;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.hl7.cql.model.DataType;
import org.hl7.elm.r1.ExpressionDef;
import org.hl7.elm.r1.VersionedIdentifier;
import org.hl7.elm.r1.Library.Statements;
import org.opencds.cqf.cql.ls.CqlLanguageServer;
import org.opencds.cqf.cql.ls.CqlUtilities;
import org.opencds.cqf.cql.ls.VersionedContent;
import org.opencds.cqf.cql.ls.provider.WorkspaceLibrarySourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CqlTextDocumentService implements TextDocumentService {
    private static final Logger Log = LoggerFactory.getLogger(CqlTextDocumentService.class);

    private final CompletableFuture<LanguageClient> client;
    private final CqlLanguageServer server;
    private final Map<URI, VersionedContent> activeDocuments = new HashMap<>();

    public CqlTextDocumentService(CompletableFuture<LanguageClient> client, CqlLanguageServer server) {
        this.client = client;
        this.server = server;
    }

    public Optional<String> activeContent(URI file) {
        return Optional.ofNullable(activeDocuments.get(file)).map(doc -> doc.content);
    }

    public Set<URI> openFiles() {
        return Sets.filter(activeDocuments.keySet(), uri -> uri.getPath().contains("Library"));
    }

    void doLint(Collection<URI> paths) {
        Log.debug("Lint " + Joiner.on(", ").join(paths));

        Map<URI, List<Diagnostic>> diagnostics = new HashMap<>();
        for (URI uri : paths) {
            Optional<String> content = activeContent(uri);
            if (content.isPresent() && content.get().length() > 0) {
                CqlTranslator translator = server.getTranslationManager().translate(uri, content.get());
                List<CqlTranslatorException> exceptions = translator.getExceptions();

                Log.debug("lint completed on {} with {} messages.", uri, exceptions.size());

                var baseUri = CqlUtilities.getHead(uri);
                // First, assign all unassociated exceptions to this library.
                for (var exception : exceptions) {
                    if (exception.getLocator() == null) {
                        exception.setLocator(
                                new TrackBack(translator.getTranslatedLibrary().getIdentifier(), 0, 0, 0, 0));
                    }
                }

                List<VersionedIdentifier> uniqueLibraries = exceptions.stream().map(x -> x.getLocator().getLibrary())
                        .distinct().filter(x -> x != null).collect(Collectors.toList());
                List<Pair<VersionedIdentifier, URI>> libraryUriList = uniqueLibraries.stream()
                        .map(x -> Pair.of(x, this.lookUpUri(baseUri, x))).collect(Collectors.toList());


                Map<VersionedIdentifier, URI> libraryUris = new HashMap<>();
                for (Pair<VersionedIdentifier, URI> p : libraryUriList) {
                    libraryUris.put(p.getLeft(), p.getRight());
                }

                // Map "unknown" libraries to the current uri
                libraryUris.put(new VersionedIdentifier().withId("unknown"), uri);

                for (var exception : exceptions) {
                    URI eUri = libraryUris.get(exception.getLocator().getLibrary());
                    if (eUri == null) {
                        continue;
                    }

                    Diagnostic d = CqlUtilities.convert(exception);

                    Log.debug("diagnostic: {} {}:{}-{}:{}: {}", eUri, d.getRange().getStart().getLine(),
                            d.getRange().getStart().getCharacter(), d.getRange().getEnd().getLine(),
                            d.getRange().getEnd().getCharacter(), d.getMessage());

                    this.addDiagnosticIfNotPresent(diagnostics, eUri, d);
                }

                this.addDiagnosticIfNotPresent(diagnostics, uri, null);
            } else {
                Diagnostic d = new Diagnostic(new Range(new Position(0, 0), new Position(0, 0)),
                        "Library does not contain CQL content.", DiagnosticSeverity.Warning, "lint");

                this.addDiagnosticIfNotPresent(diagnostics, uri, d);
            }
        }

        for (var entry : diagnostics.entrySet()) {
            PublishDiagnosticsParams params = new PublishDiagnosticsParams(entry.getKey().toString(), entry.getValue());
            client.join().publishDiagnostics(params);
        }
    }

    private URI lookUpUri(URI baseUri, VersionedIdentifier libraryIdentifier) {
        var f = WorkspaceLibrarySourceProvider.searchPath(baseUri, libraryIdentifier);
        if (f != null) {
            return f.toURI();
        }

        return null;
    };

    private void addDiagnosticIfNotPresent(Map<URI, List<Diagnostic>> diagnostics, URI uri, Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostics);
        Objects.requireNonNull(uri);

        if (!diagnostics.containsKey(uri)) {
            diagnostics.put(uri, new ArrayList<>());
        }

        if (diagnostic == null) {
            return;
        }

        var existing = diagnostics.get(uri);

        if (!existing.contains(diagnostic)) {
            existing.add(diagnostic);
        }
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams position) {
        try {

            if (position.getTextDocument() == null || position.getTextDocument().getUri() == null) {
                return CompletableFuture.completedFuture(null);
            }

            URI uri = URI.create(position.getTextDocument().getUri());
            // Optional<String> content = activeContent(uri);
            int line = position.getPosition().getLine() + 1;
            int character = position.getPosition().getCharacter() + 1;

            Log.debug("completion at {} {}:{}", uri, line, character);

            List<CompletionItem> items = new ArrayList<CompletionItem>();

            CompletionItem item = new CompletionItem();
            item.setKind(CompletionItemKind.Keyword);
            item.setLabel("declare");

            items.add(item);

            CompletionList list = new CompletionList(items);

            return CompletableFuture.completedFuture(Either.forRight(list));
        } catch (Exception e) {
            Log.error("completion: {}", e.getMessage());
            return CompletableFuture.failedFuture(e);
        }

    }

    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
        try {
            return CompletableFutures.computeAsync(cancel -> {
                // server.configured().docs.resolveCompletionItem(unresolved);

                return unresolved;
            });
        } catch (Exception e) {
            Log.error("resolveCompletionItem: ", e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }

    // TODO: Right now this just implements return type highlighting for expressions
    // only.
    // It also only works for top-level expressions.
    // This functionality should probably be part of signature help
    // So, some future work is do that and also make it work for sub-expressions.
    @Override
    public CompletableFuture<Hover> hover(HoverParams position) {
        try {
            URI uri = null;
            try {
                uri = new URI(position.getTextDocument().getUri());
            } catch (Exception e) {
                return CompletableFuture.completedFuture(null);
            }

            Optional<String> content = activeContent(uri);
            if (!content.isPresent() || content.get().length() == 0) {
                return CompletableFuture.completedFuture(null);
            }
            CqlTranslator translator = server.getTranslationManager().translate(uri, content.get());

            Pair<Range, ExpressionDef> exp = getExpressionDefForPosition(position.getPosition(),
                    translator.getTranslatedLibrary().getLibrary().getStatements());

            if (exp == null || exp.getRight().getExpression() == null) {
                return CompletableFuture.completedFuture(null);
            }

            DataType resultType = exp.getRight().getExpression().getResultType();

            Hover hover = new Hover();
            hover.setContents(Either.forLeft(List.of(Either.forRight(new MarkedString("cql", resultType.toString())))));
            hover.setRange(exp.getLeft());
            return CompletableFuture.completedFuture(hover);
        } catch (Exception e) {
            Log.error("hover: {} ", e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams position) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(DocumentHighlightParams position) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<CodeLens> resolveCodeLens(CodeLens unresolved) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        try {
            Optional<String> content = this.activeContent(new URI(params.getTextDocument().getUri()));

            if (!content.isPresent()) {
                return null;
            }

            String text = content.get();
            // Get lines from the text;
            String[] lines = text.split("[\n|\r]");

            FormatResult fr = CqlFormatterVisitor.getFormattedOutput(new ByteArrayInputStream(text.getBytes()));

            // Only update the content if it's valid CQL.
            if (fr.getErrors().size() != 0) {
                MessageParams mp = new MessageParams(MessageType.Error, "Unable to format CQL");
                this.client.join().showMessage(mp);

                return CompletableFuture.failedFuture(null);
            } else {
                int line = lines.length - 1;
                int character = lines[line].length() - 1;
                TextEdit te = new TextEdit(new Range(new Position(0, 0), new Position(lines.length, character)),
                        fr.getOutput());
                return CompletableFuture.completedFuture(Collections.singletonList(te));
            }
        } catch (Exception e) {
            MessageParams mp = new MessageParams(MessageType.Error, "Unable to format CQL");
            this.client.join().showMessage(mp);
            Log.error("formatting: {}", e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(DocumentOnTypeFormattingParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {

        try {

            if (params.getTextDocument() == null || params.getTextDocument().getUri() == null) {
                return;
            }

            TextDocumentItem document = params.getTextDocument();
            URI uri = URI.create(document.getUri());

            // TODO: filter this correctly on the client side
            if (uri.toString().contains("metadata") || uri.toString().contains("_history")) {
                return;
            }

            activeDocuments.put(uri, new VersionedContent(document.getText(), document.getVersion()));

            // TODO: Only load documents not already loaded
            // TODO: Lint documents in the workspace but not currently active
            doLint(Collections.singleton(uri));

        } catch (Exception e) {
            Log.error("didOpen for {} : {}", params.getTextDocument().getUri(), e.getMessage());
        }

    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        try {

            if (params.getTextDocument() == null || params.getTextDocument().getUri() == null) {
                return;
            }

            VersionedTextDocumentIdentifier document = params.getTextDocument();
            URI uri = URI.create(document.getUri());

            // TODO: filter this correctly on the client side
            if (uri.toString().contains("metadata") || uri.toString().contains("_history")) {
                return;
            }

            VersionedContent existing = activeDocuments.get(uri);
            String newText = existing.content;

            if (document.getVersion() > existing.version) {
                for (TextDocumentContentChangeEvent change : params.getContentChanges()) {
                    if (change.getRange() == null) {
                        activeDocuments.put(uri, new VersionedContent(change.getText(), document.getVersion()));
                    } else {
                        newText = patch(newText, change);
                        activeDocuments.put(uri, new VersionedContent(newText, document.getVersion()));
                    }
                }

                doLint(Collections.singleton(uri));
            } else {
                Log.debug("Ignored change for {} with version {} <=  {}", uri, document.getVersion(), existing.version);
            }
        } catch (Exception e) {
            Log.error("didChange for {} : {}", params.getTextDocument().getUri(), e.getMessage());
        }
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

                if (next == -1)
                    return writer.toString();
                else
                    writer.write(next);
            }
        } catch (Exception e) {
            Log.error("patch: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        if(params.getTextDocument() == null || params.getTextDocument().getUri() == null) {
            return;
        }

        try {
        TextDocumentIdentifier document = params.getTextDocument();
        URI uri = URI.create(document.getUri());

        // Remove from source cache
        activeDocuments.remove(uri);

        // Clear diagnostics
        client.join().publishDiagnostics(new PublishDiagnosticsParams(uri.toString(), new ArrayList<>()));
        }
        catch (Exception e) {
            Log.error("didClose: {}", e.getMessage());
        }
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        if(params.getTextDocument() == null || params.getTextDocument().getUri() == null) {
            return;
        }

        try {
            // Re-lint all active documents
            doLint(openFiles());

        }
        catch (Exception e) {
            Log.error("didSave: {}", e.getMessage());
        }
    }

    private Pair<Range, ExpressionDef> getExpressionDefForPosition(Position position, Statements statements) {
        if (statements == null || statements.getDef() == null || statements.getDef().size() == 0) {
            return null;
        }

        for (ExpressionDef def : statements.getDef()) {
            if (def.getTrackbacks() == null || def.getTrackbacks().size() == 0) {
                continue;
            }

            for (TrackBack tb : def.getTrackbacks()) {
                if (positionInTrackBack(position, tb)) {
                    Range range = new Range(new Position(tb.getStartLine() - 1, tb.getStartChar() - 1),
                            new Position(tb.getEndLine() - 1, tb.getEndChar()));
                    return Pair.of(range, def);
                }
            }
        }

        return null;
    }

    private boolean positionInTrackBack(Position p, TrackBack tb) {
        int startLine = tb.getStartLine() - 1;
        int endLine = tb.getEndLine() - 1;

        // Just kidding. We need intervals.
        if (p.getLine() >= startLine && p.getLine() <= endLine) {
            return true;
        } else {
            return false;
        }

    }
}
