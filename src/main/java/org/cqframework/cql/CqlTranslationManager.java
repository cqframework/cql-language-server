package org.cqframework.cql;

import org.cqframework.cql.cql2elm.*;
import org.fhir.ucum.UcumService;

import java.util.List;

/**
 * Created by Bryn on 9/4/2018.
 */
public class CqlTranslationManager {
    private final ModelManager modelManager;
    private final LibraryManager libraryManager;
    private final UcumService ucumService = null;

    public CqlTranslationManager(LibrarySourceProvider librarySourceProvider) {
        modelManager = new ModelManager();
        libraryManager = new LibraryManager(modelManager);
        // TODO: validateUnits setting

        libraryManager.getLibrarySourceLoader().registerProvider(librarySourceProvider);
    }

    public List<CqlTranslatorException> translate(String content) {
        // TODO: translator settings
        CqlTranslator translator = CqlTranslator.fromText(content, modelManager, libraryManager);
        // TODO: cache translation result...
        return translator.getExceptions();
    }
}
