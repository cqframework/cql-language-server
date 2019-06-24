package org.cqframework.cql;

import org.cqframework.cql.cql2elm.*;
import org.cqframework.cql.cql2elm.model.Model;
import org.cqframework.cql.manager.CacheAwareModelManager;
import org.hl7.elm.r1.VersionedIdentifier;
import org.fhir.ucum.UcumService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by Bryn on 9/4/2018.
 */
public class CqlTranslationManager {
    private final Map<VersionedIdentifier, Model> modelCache = new HashMap<>();

    private final LibrarySourceProvider librarySourceProvider;


    public CqlTranslationManager(LibrarySourceProvider librarySourceProvider) {
        // TODO: validateUnits setting
        this.librarySourceProvider = librarySourceProvider;
    }

    public List<CqlTranslatorException> translate(String content) {
        ModelManager modelManager = new CacheAwareModelManager(this.modelCache);
        LibraryManager libraryManager = new NonCachingLibraryManager(modelManager);
        libraryManager.getLibrarySourceLoader().registerProvider(librarySourceProvider);

        CqlTranslator translator = CqlTranslator.fromText(content, modelManager, libraryManager,
            CqlTranslator.Options.EnableAnnotations,
            CqlTranslator.Options.EnableLocators,
            CqlTranslator.Options.DisableListDemotion,
            CqlTranslator.Options.DisableListPromotion,
            CqlTranslator.Options.DisableMethodInvocation);
        // TODO: cache translation result...
        return translator.getExceptions();
    }
}
