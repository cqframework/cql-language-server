package org.opencds.cqf.cql.ls.service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;

import com.google.common.base.Joiner;
import com.google.gson.JsonElement;

import org.apache.commons.lang3.tuple.Pair;
import org.cqframework.cql.cql2elm.CqlTranslator;
import org.cqframework.cql.elm.tracking.TrackBack;
import org.cqframework.cql.tools.formatter.CqlFormatterVisitor;
import org.cqframework.cql.tools.formatter.CqlFormatterVisitor.FormatResult;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.hl7.cql.model.DataType;
import org.hl7.elm.r1.ExpressionDef;
import org.hl7.elm.r1.Library.Statements;
import org.opencds.cqf.cql.ls.ActiveContent;
import org.opencds.cqf.cql.ls.DebounceExecutor;
import org.opencds.cqf.cql.ls.FuturesHelper;
import org.opencds.cqf.cql.ls.VersionedContent;
import org.opencds.cqf.cql.ls.manager.CqlTranslationManager;
import org.opencds.cqf.cql.ls.plugin.CommandContribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CqlTextDocumentService implements TextDocumentService {
    private static final Logger logger = LoggerFactory.getLogger(CqlTextDocumentService.class);
    private static final long BOUNCE_DELAY = 200;

    private final CompletableFuture<LanguageClient> client;
    private final ActiveContent activeContent;
    private final CqlTranslationManager cqlTranslationManager;

    private DebounceExecutor debouncer;

    private DebounceExecutor getDebouncer() {
        if (debouncer == null) {
            debouncer = new DebounceExecutor();
        }
        return debouncer;
    }

    public CqlTextDocumentService(CompletableFuture<LanguageClient> client, ActiveContent activeContent,
            CqlTranslationManager cqlTranslationManager) {
        this.client = client;
        this.activeContent = activeContent;
        this.cqlTranslationManager = cqlTranslationManager;
    }

    protected void doLint(Collection<URI> paths) {
        logger.debug("Lint " + Joiner.on(", ").join(paths));

        Map<URI, Set<Diagnostic>> allDiagnostics = new HashMap<>();
        for (URI uri : paths) {
            Map<URI, Set<Diagnostic>> currentDiagnostics = this.cqlTranslationManager.lint(uri);
            this.mergeDiagnostics(allDiagnostics, currentDiagnostics);
        }

        for (Map.Entry<URI, Set<Diagnostic>> entry : allDiagnostics.entrySet()) {
            PublishDiagnosticsParams params = new PublishDiagnosticsParams(entry.getKey().toString(),
                    new ArrayList<>(entry.getValue()));
            client.join().publishDiagnostics(params);
        }
    }

    // @Override
    // public CompletableFuture<Either<List<CompletionItem>, CompletionList>>
    // completion(CompletionParams position) {
    // if (position.getTextDocument() == null || position.getTextDocument().getUri()
    // == null) {
    // return CompletableFuture.completedFuture(null);
    // }

    // try {

    // URI uri = URI.create(position.getTextDocument().getUri());
    // // Optional<String> content = activeContent(uri);
    // int line = position.getPosition().getLine() + 1;
    // int character = position.getPosition().getCharacter() + 1;

    // Log.debug("completion at {} {}:{}", uri, line, character);

    // List<CompletionItem> items = new ArrayList<CompletionItem>();

    // CompletionItem item = new CompletionItem();
    // item.setKind(CompletionItemKind.Keyword);
    // item.setLabel("declare");

    // items.add(item);

    // CompletionList list = new CompletionList(items);

    // return CompletableFuture.completedFuture(Either.forRight(list));
    // } catch (Exception e) {
    // Log.error("completion: {}", e.getMessage());
    // return FuturesHelper.failedFuture(e);
    // }

    // }

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

            CqlTranslator translator = this.cqlTranslationManager.translate(uri);
            if (translator == null) {
                return CompletableFuture.completedFuture(null);
            }


            Pair<Range, ExpressionDef> exp = getExpressionDefForPosition(position.getPosition(),
                    translator.getTranslatedLibrary().getLibrary().getStatements());

            if (exp == null || exp.getRight().getExpression() == null) {
                return CompletableFuture.completedFuture(null);
            }

            DataType resultType = exp.getRight().getExpression().getResultType();

            Hover hover = new Hover();
            hover.setContents(Either.forRight(new MarkupContent("markdown", "```" + resultType.toString() + "```")));
            hover.setRange(exp.getLeft());
            return CompletableFuture.completedFuture(hover);
        } catch (Exception e) {
            logger.error("hover: {} ", e.getMessage());
            return FuturesHelper.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        try {

            URI uri = URI.create(params.getTextDocument().getUri());
            if (!this.activeContent.contains(uri)) {
                return CompletableFuture.completedFuture(null);
            }

            String content = this.activeContent.get(uri).content;
            // Get lines from the text;
            String[] lines = content.split("[\n|\r]");

            FormatResult fr = CqlFormatterVisitor.getFormattedOutput(new ByteArrayInputStream(content.getBytes()));

            // Only update the content if it's valid CQL.
            if (fr.getErrors().size() != 0) {
                MessageParams mp = new MessageParams(MessageType.Error, "Unable to format CQL");
                this.client.join().showMessage(mp);

                return CompletableFuture.completedFuture(Collections.emptyList());
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
            logger.error("formatting: {}", e.getMessage());
            return FuturesHelper.failedFuture(e);
        }
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

            activeContent.put(uri, new VersionedContent(document.getText(), document.getVersion()));
            doLint(Collections.singleton(uri));

        } catch (Exception e) {
            logger.error("didOpen for {} : {}", params.getTextDocument().getUri(), e.getMessage());
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

            VersionedContent existing = activeContent.get(uri);
            String newText = existing.content;

            if (document.getVersion() > existing.version) {
                for (TextDocumentContentChangeEvent change : params.getContentChanges()) {
                    if (change.getRange() == null) {
                        activeContent.put(uri, new VersionedContent(change.getText(), document.getVersion()));
                    } else {
                        newText = patch(newText, change);
                        activeContent.put(uri, new VersionedContent(newText, document.getVersion()));
                    }
                }

                getDebouncer().debounce(BOUNCE_DELAY, () -> doLint(Collections.singleton(uri)));
            } else {
                logger.debug("Ignored change for {} with version {} <=  {}", uri, document.getVersion(),
                        existing.version);
            }
        } catch (Exception e) {
            logger.error("didChange for {} : {}", params.getTextDocument().getUri(), e.getMessage());
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        if (params.getTextDocument() == null || params.getTextDocument().getUri() == null) {
            return;
        }

        try {
            TextDocumentIdentifier document = params.getTextDocument();
            URI uri = URI.create(document.getUri());

            // Remove from source cache
            activeContent.remove(uri);

            // Clear diagnostics
            client.join().publishDiagnostics(new PublishDiagnosticsParams(uri.toString(), new ArrayList<>()));
        } catch (Exception e) {
            logger.error("didClose: {}", e.getMessage());
        }
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        if (params.getTextDocument() == null || params.getTextDocument().getUri() == null) {
            return;
        }

        try {
            // TODO: What we really want here is to lint documents that depended on this
            // one.
            // To do that effectively requires an index of the inter-dependencies in the
            // workspace.
            doLint(Collections.list(this.activeContent.keys()));
        } catch (Exception e) {
            logger.error("didSave: {}", e.getMessage());
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

    @SuppressWarnings("deprecation")
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
            logger.error("patch: {}", e.getMessage());
            return null;
        }
    }

    private void mergeDiagnostics(Map<URI, Set<Diagnostic>> currentDiagnostics,
            Map<URI, Set<Diagnostic>> newDiagnostics) {
        Objects.requireNonNull(currentDiagnostics);
        Objects.requireNonNull(newDiagnostics);

        for (Entry<URI, Set<Diagnostic>> entry : newDiagnostics.entrySet()) {
            Set<Diagnostic> currentSet = currentDiagnostics.computeIfAbsent(entry.getKey(), k -> new HashSet<>());
            for (Diagnostic d : entry.getValue()) {
                currentSet.add(d);
            }
        }
    }

    public CommandContribution getCommandContribution() {
        return new TextDocumentServiceCommandContribution();
    }

    private class TextDocumentServiceCommandContribution implements CommandContribution {
        private static final String VIEW_ELM_COMMAND = "org.opencds.cqf.cql.ls.viewElm";

        @Override
        public Set<String> getCommands() {
            return Collections.singleton(VIEW_ELM_COMMAND);
        }

        @Override
        public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
            switch (params.getCommand()) {
                case VIEW_ELM_COMMAND:
                    return this.viewElm(params);
                default:
                    return CommandContribution.super.executeCommand(params);
            }
        }

        // There's currently not a "show text file" or similar command in the LSP spec,
        // So it's not client agnostic. The client has to know that the result of this
        // command
        // is XML and display it accordingly.
        private CompletableFuture<Object> viewElm(ExecuteCommandParams params) {
            String uriString = ((JsonElement) params.getArguments().get(0)).getAsString();
            URI uri = URI.create(uriString);
            CqlTranslator translator = CqlTextDocumentService.this.cqlTranslationManager.translate(uri);
            if (translator != null) {
                return CompletableFuture.completedFuture(translator.toXml());
            }

            return CompletableFuture.completedFuture(null);
        }
    }
}
