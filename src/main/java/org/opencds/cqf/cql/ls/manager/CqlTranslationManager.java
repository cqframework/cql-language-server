package org.opencds.cqf.cql.ls.manager;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.cqframework.cql.cql2elm.CqlTranslator;
import org.cqframework.cql.cql2elm.CqlTranslatorOptions;
import org.cqframework.cql.cql2elm.FhirLibrarySourceProvider;
import org.cqframework.cql.cql2elm.LibraryManager;
import org.cqframework.cql.cql2elm.ModelManager;
import org.cqframework.cql.cql2elm.model.Model;
import org.hl7.elm.r1.VersionedIdentifier;
import org.opencds.cqf.cql.ls.CqlUtilities;
import org.opencds.cqf.cql.ls.provider.ActiveContentLibrarySourceProvider;
import org.opencds.cqf.cql.ls.provider.FhirServerLibrarySourceProvider;
import org.opencds.cqf.cql.ls.provider.WorkspaceLibrarySourceProvider;
import org.opencds.cqf.cql.ls.service.CqlTextDocumentService;

public class CqlTranslationManager {

    private final CqlTextDocumentService textDocumentService;

    private final Map<VersionedIdentifier, Model> globalCache;

    public CqlTranslationManager(CqlTextDocumentService textDocumentService) {
        this.globalCache = new HashMap<>();
        this.textDocumentService = textDocumentService;
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
        LibraryManager libraryManager = new NonCachingLibraryManager(modelManager);

        URI baseUri = CqlUtilities.getHead(uri);

        libraryManager.getLibrarySourceLoader().registerProvider(new ActiveContentLibrarySourceProvider(baseUri, this.textDocumentService));
        libraryManager.getLibrarySourceLoader().registerProvider(new WorkspaceLibrarySourceProvider(baseUri));
        libraryManager.getLibrarySourceLoader().registerProvider(new FhirServerLibrarySourceProvider(baseUri));
        libraryManager.getLibrarySourceLoader().registerProvider(new FhirLibrarySourceProvider());

        return libraryManager;
    }
}
