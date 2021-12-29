package org.opencds.cqf.cql.ls.manager;

import java.io.File;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;
import org.cqframework.cql.cql2elm.CqlTranslator;
import org.cqframework.cql.cql2elm.CqlTranslatorException;
import org.cqframework.cql.cql2elm.CqlTranslatorOptions;
import org.cqframework.cql.cql2elm.FhirLibrarySourceProvider;
import org.cqframework.cql.cql2elm.LibraryManager;
import org.cqframework.cql.cql2elm.ModelManager;
import org.cqframework.cql.cql2elm.model.Model;
import org.cqframework.cql.elm.tracking.TrackBack;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.hl7.elm.r1.VersionedIdentifier;
import org.opencds.cqf.cql.ls.ActiveContent;
import org.opencds.cqf.cql.ls.CqlUtilities;
import org.opencds.cqf.cql.ls.provider.ActiveContentLibrarySourceProvider;
import org.opencds.cqf.cql.ls.provider.WorkspaceLibrarySourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CqlTranslationManager {
    private final Map<VersionedIdentifier, Model> globalCache;
    private final ActiveContent activeContent;

    private static final Logger logger = LoggerFactory.getLogger(CqlTranslationManager.class);

    public CqlTranslationManager(ActiveContent activeContent) {
        this.globalCache = new HashMap<>();
        this.activeContent = activeContent;
    }

    public CqlTranslator translate(URI uri) {
        // TODO: Support translating from disk
        if (this.activeContent.containsKey(uri)) {
            return this.translate(uri, this.activeContent.get(uri).content);
        }

        return null;
    }

    public CqlTranslator translate(URI uri, String content) {
        ModelManager modelManager = this.createModelManager();
        LibraryManager libraryManager = this.createLibraryManager(uri, modelManager);

        return CqlTranslator.fromText(content, modelManager, libraryManager, null, CqlTranslatorOptions.defaultOptions());
    }

    private ModelManager createModelManager() {
        return new CacheAwareModelManager(this.globalCache);
    }

    private LibraryManager createLibraryManager(URI uri, ModelManager modelManager) {
        LibraryManager libraryManager = new LibraryManager(modelManager);

        URI baseUri = CqlUtilities.getHead(uri);

        libraryManager.getLibrarySourceLoader().registerProvider(new ActiveContentLibrarySourceProvider(baseUri, this.activeContent));
        libraryManager.getLibrarySourceLoader().registerProvider(new WorkspaceLibrarySourceProvider(baseUri));
        libraryManager.getLibrarySourceLoader().registerProvider(new FhirLibrarySourceProvider());

        return libraryManager;
    }

    public Map<URI, List<Diagnostic>> lint(URI uri) {
        Map<URI, List<Diagnostic>> diagnostics = new HashMap<>();
        CqlTranslator translator = this.translate(uri);
        if (translator == null) {
            Diagnostic d = new Diagnostic(new Range(new Position(0, 0), new Position(0, 0)),
                    "Library does not contain CQL content.", DiagnosticSeverity.Warning, "lint");

            diagnostics.computeIfAbsent(uri, k -> new ArrayList<>()).add(d);

            return diagnostics;
        }

        List<CqlTranslatorException> exceptions = translator.getExceptions();

        logger.debug("lint completed on {} with {} messages.", uri, exceptions.size());

        URI baseUri = CqlUtilities.getHead(uri);
        // First, assign all unassociated exceptions to this library.
        for (CqlTranslatorException exception : exceptions) {
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

        for (CqlTranslatorException exception : exceptions) {
            URI eUri = libraryUris.get(exception.getLocator().getLibrary());
            if (eUri == null) {
                continue;
            }

            Diagnostic d = CqlUtilities.convert(exception);

            logger.debug("diagnostic: {} {}:{}-{}:{}: {}", eUri, d.getRange().getStart().getLine(),
                    d.getRange().getStart().getCharacter(), d.getRange().getEnd().getLine(),
                    d.getRange().getEnd().getCharacter(), d.getMessage());


            diagnostics.computeIfAbsent(eUri, k -> new ArrayList<>()).add(d);
        }

        // Ensure there is an entry for the library in the case that there are no exceptions
        diagnostics.computeIfAbsent(uri, k -> new ArrayList<>());

        return diagnostics;
    }

    private URI lookUpUri(URI baseUri, VersionedIdentifier libraryIdentifier) {
        File f = WorkspaceLibrarySourceProvider.searchPath(baseUri, libraryIdentifier);
        if (f != null) {
            return f.toURI();
        }

        return null;
    };
}
