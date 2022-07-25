package org.opencds.cqf.cql.ls.server.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.Pair;
import org.cqframework.cql.cql2elm.CqlTranslator;
import org.cqframework.cql.cql2elm.CqlTranslatorException;
import org.cqframework.cql.elm.tracking.TrackBack;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.services.LanguageClient;
import org.greenrobot.eventbus.Subscribe;
import org.hl7.elm.r1.VersionedIdentifier;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.DebounceExecutor;
import org.opencds.cqf.cql.ls.server.event.DidChangeTextDocumentEvent;
import org.opencds.cqf.cql.ls.server.event.DidCloseTextDocumentEvent;
import org.opencds.cqf.cql.ls.server.event.DidOpenTextDocumentEvent;
import org.opencds.cqf.cql.ls.server.manager.CqlTranslationManager;
import org.opencds.cqf.cql.ls.server.utility.Diagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.base.Joiner;

public class DiagnosticsService {

    private static Logger log = LoggerFactory.getLogger(DiagnosticsService.class);

    private static final long BOUNCE_DELAY = 200;

    private CqlTranslationManager cqlTranslationManager;
    private CompletableFuture<LanguageClient> client;
    private ContentService contentService;

    private DebounceExecutor debouncer;

    private DebounceExecutor getDebouncer() {
        if (debouncer == null) {
            debouncer = new DebounceExecutor();
        }
        return debouncer;
    }

    public DiagnosticsService(CompletableFuture<LanguageClient> client,
            CqlTranslationManager cqlTranslationManager, ContentService contentService) {
        this.client = client;
        this.cqlTranslationManager = cqlTranslationManager;
        this.contentService = contentService;
    }

    protected void doLint(Collection<URI> paths) {
        if (log.isDebugEnabled()) {
            log.debug("Lint: {}", Joiner.on(", ").join(paths));
        }

        Map<URI, Set<Diagnostic>> allDiagnostics = new HashMap<>();
        for (URI uri : paths) {
            Map<URI, Set<Diagnostic>> currentDiagnostics = this.lint(uri);
            this.mergeDiagnostics(allDiagnostics, currentDiagnostics);
        }

        for (Map.Entry<URI, Set<Diagnostic>> entry : allDiagnostics.entrySet()) {
            PublishDiagnosticsParams params = new PublishDiagnosticsParams(
                    entry.getKey().toString(), new ArrayList<>(entry.getValue()));
            client.join().publishDiagnostics(params);
        }
    }

    private void mergeDiagnostics(Map<URI, Set<Diagnostic>> currentDiagnostics,
            Map<URI, Set<Diagnostic>> newDiagnostics) {
        Objects.requireNonNull(currentDiagnostics);
        Objects.requireNonNull(newDiagnostics);

        for (Entry<URI, Set<Diagnostic>> entry : newDiagnostics.entrySet()) {
            Set<Diagnostic> currentSet =
                    currentDiagnostics.computeIfAbsent(entry.getKey(), k -> new HashSet<>());
            for (Diagnostic d : entry.getValue()) {
                currentSet.add(d);
            }
        }
    }

    public Map<URI, Set<Diagnostic>> lint(URI uri) {
        Map<URI, Set<Diagnostic>> diagnostics = new HashMap<>();
        CqlTranslator translator = this.cqlTranslationManager.translate(uri);
        if (translator == null) {
            Diagnostic d = new Diagnostic(new Range(new Position(0, 0), new Position(0, 0)),
                    "Library does not contain CQL content.", DiagnosticSeverity.Warning, "lint");

            diagnostics.computeIfAbsent(uri, k -> new HashSet<>()).add(d);

            return diagnostics;
        }

        List<CqlTranslatorException> exceptions = translator.getExceptions();

        log.debug("lint completed on {} with {} messages.", uri, exceptions.size());

        // First, assign all unassociated exceptions to this library.
        for (CqlTranslatorException exception : exceptions) {
            if (exception.getLocator() == null) {
                exception.setLocator(new TrackBack(
                        translator.getTranslatedLibrary().getIdentifier(), 0, 0, 0, 0));
            }
        }

        List<VersionedIdentifier> uniqueLibraries =
                exceptions.stream().map(x -> x.getLocator().getLibrary()).distinct()
                        .filter(Objects::nonNull).collect(Collectors.toList());

        // TODO: Due to the way the content service is implemented, this will scan the entire
        // project
        // to locate a given versioned identifier for every library in the list here. Thats ~N^2
        // file accesses, so that's bad.
        List<Pair<VersionedIdentifier, URI>> libraryUriList =
                uniqueLibraries.stream().map(x -> Pair.of(x, this.contentService.locate(x).get(0)))
                        .collect(Collectors.toList());

        Map<VersionedIdentifier, URI> libraryUris = new HashMap<>();
        for (Pair<VersionedIdentifier, URI> p : libraryUriList) {
            libraryUris.put(p.getLeft(), p.getRight());
        }

        // Map "unknown" libraries to the current uri
        libraryUris.put(new VersionedIdentifier().withId("unknown"), uri);

        for (CqlTranslatorException exception : exceptions) {
            URI eUri = libraryUris.get(exception.getLocator().getLibrary());
            if (eUri == null) {
                continue;
            }

            Diagnostic d = Diagnostics.convert(exception);

            log.debug("diagnostic: {} {}:{}-{}:{}: {}", eUri, d.getRange().getStart().getLine(),
                    d.getRange().getStart().getCharacter(), d.getRange().getEnd().getLine(),
                    d.getRange().getEnd().getCharacter(), d.getMessage());

            diagnostics.computeIfAbsent(eUri, k -> new HashSet<>()).add(d);
        }

        // Ensure there is an entry for the library in the case that there are no
        // exceptions
        diagnostics.computeIfAbsent(uri, k -> new HashSet<>());

        return diagnostics;
    }


    @Subscribe
    public void didOpen(DidOpenTextDocumentEvent e) {
        doLint(Collections.singletonList(Uris.parseOrNull(e.params().getTextDocument().getUri())));
    }

    @Subscribe
    public void didClose(DidCloseTextDocumentEvent e) {
        PublishDiagnosticsParams params = new PublishDiagnosticsParams(
                e.params().getTextDocument().getUri(), new ArrayList<>());
        client.join().publishDiagnostics(params);
    }

    @Subscribe
    public void didChange(DidChangeTextDocumentEvent e) {
        getDebouncer().debounce(BOUNCE_DELAY, () -> doLint(Collections
                .singletonList(Uris.parseOrNull(e.params().getTextDocument().getUri()))));
    }
}
