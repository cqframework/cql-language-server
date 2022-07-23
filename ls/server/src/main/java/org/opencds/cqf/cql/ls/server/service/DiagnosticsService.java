package org.opencds.cqf.cql.ls.server.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.greenrobot.eventbus.Subscribe;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.DebounceExecutor;
import org.opencds.cqf.cql.ls.server.event.DidChangeTextDocumentEvent;
import org.opencds.cqf.cql.ls.server.event.DidCloseTextDocumentEvent;
import org.opencds.cqf.cql.ls.server.event.DidOpenTextDocumentEvent;
import org.opencds.cqf.cql.ls.server.manager.CqlTranslationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.base.Joiner;

public class DiagnosticsService {

    private static Logger log = LoggerFactory.getLogger(DiagnosticsService.class);

    private static final long BOUNCE_DELAY = 200;

    private CqlTranslationManager cqlTranslationManager;
    private CompletableFuture<LanguageClient> client;

    private DebounceExecutor debouncer;

    private DebounceExecutor getDebouncer() {
        if (debouncer == null) {
            debouncer = new DebounceExecutor();
        }
        return debouncer;
    }

    public DiagnosticsService(CompletableFuture<LanguageClient> client,
            CqlTranslationManager cqlTranslationManager) {
        this.client = client;
        this.cqlTranslationManager = cqlTranslationManager;
    }

    protected void doLint(Collection<URI> paths) {
        if (log.isDebugEnabled()) {
            log.debug("Lint: {}", Joiner.on(", ").join(paths));
        }

        Map<URI, Set<Diagnostic>> allDiagnostics = new HashMap<>();
        for (URI uri : paths) {
            Map<URI, Set<Diagnostic>> currentDiagnostics = this.cqlTranslationManager.lint(uri);
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
