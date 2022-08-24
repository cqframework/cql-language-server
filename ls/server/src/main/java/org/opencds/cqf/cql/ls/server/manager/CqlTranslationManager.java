package org.opencds.cqf.cql.ls.server.manager;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.cqframework.cql.cql2elm.CqlTranslator;
import org.cqframework.cql.cql2elm.FhirLibrarySourceProvider;
import org.cqframework.cql.cql2elm.LibraryManager;
import org.cqframework.cql.cql2elm.ModelManager;
import org.cqframework.cql.cql2elm.model.Model;
import org.fhir.ucum.UcumEssenceService;
import org.fhir.ucum.UcumService;
import org.hl7.elm.r1.VersionedIdentifier;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.provider.ContentServiceSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CqlTranslationManager {
    private static final Logger log = LoggerFactory.getLogger(CqlTranslationManager.class);

    private final Map<VersionedIdentifier, Model> globalCache;
    private final ContentService contentService;
    private static UcumService ucumService = null;
    private final TranslatorOptionsManager translatorOptionsManager;

    static {
        try {
            ucumService = new UcumEssenceService(
                    UcumEssenceService.class.getResourceAsStream("/ucum-essence.xml"));
        } catch (Exception e) {
            log.warn("error initializing UcumService", e);
        }
    }

    public CqlTranslationManager(ContentService contentService,
            TranslatorOptionsManager translatorOptionsManager) {
        this.globalCache = new HashMap<>();
        this.contentService = contentService;
        this.translatorOptionsManager = translatorOptionsManager;
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

        LibraryManager libraryManager = this.createLibraryManager(Uris.getHead(uri), modelManager);

        try {
            return CqlTranslator.fromStream(stream, modelManager, libraryManager, ucumService,
                    this.translatorOptionsManager.getOptions(uri));
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    String.format("error creating translator for uri: %s", uri.toString()), e);
        }
    }

    private ModelManager createModelManager() {
        return new CacheAwareModelManager(this.globalCache);
    }

    private LibraryManager createLibraryManager(URI root, ModelManager modelManager) {
        LibraryManager libraryManager = new LibraryManager(modelManager);
        libraryManager.getLibrarySourceLoader()
                .registerProvider(new ContentServiceSourceProvider(root, this.contentService));
        libraryManager.getLibrarySourceLoader().registerProvider(new FhirLibrarySourceProvider());

        return libraryManager;
    }
}
