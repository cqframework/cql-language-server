package org.opencds.cqf.cql.ls.server.manager;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import org.fhir.ucum.UcumEssenceService;
import org.fhir.ucum.UcumService;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.hl7.elm.r1.VersionedIdentifier;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.server.CqlUtilities;
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent;
import org.opencds.cqf.cql.ls.server.provider.ContentServiceSourceProvider;
import org.opencds.cqf.cql.ls.server.utility.Diagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CqlTranslationManager {
    private static final Logger log = LoggerFactory.getLogger(CqlTranslationManager.class);

    private final Map<VersionedIdentifier, Model> globalCache;
    private final ContentService contentService;
    private static UcumService ucumService = null;

    static {
        try {
            ucumService = new UcumEssenceService(
                    UcumEssenceService.class.getResourceAsStream("/ucum-essence.xml"));
        } catch (Exception e) {
            log.warn("error initializing UcumService", e);
        }
    }

    public CqlTranslationManager(ContentService contentService) {

        this.globalCache = new HashMap<>();
        this.contentService = contentService;
    }

    public CqlTranslator translate(URI uri) {
        InputStream input = contentService.read(uri);
        if (input == null) {
            return null;
        }

        return this.translate(uri, input);
    }

    public CqlTranslator translate(URI uri, InputStream stream) {
        ModelManager modelManager = this.createModelManager();

        LibraryManager libraryManager = this.createLibraryManager(modelManager);

        try {
            return CqlTranslator.fromStream(stream, modelManager, libraryManager, ucumService,
                    getTranslatorOptions(uri));
        } catch (IOException e) {
            throw new RuntimeException(
                    String.format("error creating translator for uri: %s", uri.toString()), e);
        }
    }

    private CqlTranslatorOptions cachedOptions = null;

    private CqlTranslatorOptions getTranslatorOptions(URI uri) {
        if (cachedOptions == null) {
            cachedOptions = CqlUtilities.getTranslatorOptions(contentService, uri);
        }
        return cachedOptions;
    }

    public void clearCachedTranslatorOptions() {
        cachedOptions = null;
    }

    private ModelManager createModelManager() {
        return new CacheAwareModelManager(this.globalCache);
    }

    private LibraryManager createLibraryManager(ModelManager modelManager) {
        LibraryManager libraryManager = new LibraryManager(modelManager);
        libraryManager.getLibrarySourceLoader()
                .registerProvider(new ContentServiceSourceProvider(this.contentService));
        libraryManager.getLibrarySourceLoader().registerProvider(new FhirLibrarySourceProvider());

        return libraryManager;
    }

    public Map<URI, Set<Diagnostic>> lint(URI uri) {
        Map<URI, Set<Diagnostic>> diagnostics = new HashMap<>();
        CqlTranslator translator = this.translate(uri);
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

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onMessageEvent(DidChangeWatchedFilesEvent event) {
        this.clearCachedTranslatorOptions();
    }
}
