package org.cqframework.cql.manager;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.cqframework.cql.CqlUtilities;
import org.cqframework.cql.cql2elm.CqlTranslator;
import org.cqframework.cql.cql2elm.FhirLibrarySourceProvider;
import org.cqframework.cql.cql2elm.LibraryManager;
import org.cqframework.cql.cql2elm.ModelManager;
import org.cqframework.cql.cql2elm.model.Model;
import org.cqframework.cql.provider.ActiveContentLibrarySourceProvider;
import org.cqframework.cql.provider.FhirServerLibrarySourceProvider;
import org.cqframework.cql.provider.WorkspaceLibrarySourceProvider;
import org.cqframework.cql.service.CqlTextDocumentService;
import org.cqframework.cql.service.CqlWorkspaceService;
import org.hl7.elm.r1.VersionedIdentifier;

public class CqlTranslationManager {

    private final CqlWorkspaceService workspaceService;
    private final CqlTextDocumentService textDocumentService;

    private final Map<VersionedIdentifier, Model> globalCache;

    public CqlTranslationManager(CqlTextDocumentService textDocumentService, CqlWorkspaceService workspaceService) {
        this.globalCache = new HashMap<>();
        this.workspaceService = workspaceService;
        this.textDocumentService = textDocumentService;
    }

    public CqlTranslator translate(URI uri, String content) {
        ModelManager modelManager = this.createModelManager();
        LibraryManager libraryManager = this.createLibraryManager(uri, modelManager);

        return CqlTranslator.fromText(content, modelManager, libraryManager);
    }

    private ModelManager createModelManager() {
        return new CacheAwareModelManager(this.globalCache);
    }

    private LibraryManager createLibraryManager(URI uri, ModelManager modelManager) {
        LibraryManager libraryManager = new LibraryManager(modelManager);
        // TODO: validateUnits setting

        URI baseUri = CqlUtilities.getHead(uri);

        libraryManager.getLibrarySourceLoader().registerProvider(new ActiveContentLibrarySourceProvider(baseUri, this.textDocumentService));
        libraryManager.getLibrarySourceLoader().registerProvider(new WorkspaceLibrarySourceProvider(baseUri));
        libraryManager.getLibrarySourceLoader().registerProvider(new FhirServerLibrarySourceProvider(baseUri));
        libraryManager.getLibrarySourceLoader().registerProvider(new FhirLibrarySourceProvider());

        return libraryManager;
    }
}
