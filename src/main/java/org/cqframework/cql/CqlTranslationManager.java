package org.cqframework.cql;

import org.cqframework.cql.cql2elm.*;
import org.cqframework.cql.source.*;
import org.fhir.ucum.UcumService;

import java.util.List;

/**
 * Created by Bryn on 9/4/2018.
 */
public class CqlTranslationManager {
    private final ModelManager modelManager;
    private final LibraryManager libraryManager;

    public CqlTranslationManager(CqlTextDocumentService textDocumentService, CqlWorkspaceService workspaceService) {
        modelManager = new ModelManager();
        libraryManager = new NonCachingLibraryManager(modelManager);
        // TODO: validateUnits setting

        libraryManager.getLibrarySourceLoader().registerProvider(new ActiveContentLibrarySourceProvider(textDocumentService));
        libraryManager.getLibrarySourceLoader().registerProvider(new WorkspaceLibrarySourceProvider(workspaceService));
        libraryManager.getLibrarySourceLoader().registerProvider(new FhirServerLibrarySourceProvider(workspaceService));
        libraryManager.getLibrarySourceLoader().registerProvider(new FhirLibrarySourceProvider());
    }

    public CqlTranslator translate(String content) {
            return CqlTranslator.fromText(content, modelManager, libraryManager);
    }
}
