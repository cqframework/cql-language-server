package org.opencds.cqf.cql.ls.server.manager;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.cqframework.cql.cql2elm.CqlTranslator;
import org.cqframework.cql.cql2elm.CqlTranslatorOptions;
import org.cqframework.cql.cql2elm.FhirLibrarySourceProvider;
import org.cqframework.cql.cql2elm.LibraryManager;
import org.cqframework.cql.cql2elm.ModelManager;
import org.cqframework.cql.cql2elm.model.Model;
import org.fhir.ucum.UcumEssenceService;
import org.fhir.ucum.UcumService;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.hl7.elm.r1.VersionedIdentifier;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.server.CqlUtilities;
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent;
import org.opencds.cqf.cql.ls.server.provider.ContentServiceSourceProvider;
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

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onMessageEvent(DidChangeWatchedFilesEvent event) {
        this.clearCachedTranslatorOptions();
    }
}
