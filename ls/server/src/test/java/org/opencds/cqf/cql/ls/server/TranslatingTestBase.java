package org.opencds.cqf.cql.ls.server;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;

import javax.xml.bind.JAXBException;

import org.cqframework.cql.cql2elm.CqlTranslator;
import org.cqframework.cql.cql2elm.FhirLibrarySourceProvider;
import org.cqframework.cql.cql2elm.LibraryManager;
import org.cqframework.cql.cql2elm.ModelManager;
import org.fhir.ucum.UcumEssenceService;
import org.fhir.ucum.UcumException;
import org.fhir.ucum.UcumService;
import org.testng.annotations.BeforeClass;

public abstract class TranslatingTestBase {

    protected CqlTranslator cqlTranslator;

    private static ModelManager modelManager;

    protected static ModelManager getModelManager() {
        if (modelManager == null) {
            modelManager = new ModelManager();
        }

        return modelManager;
    }

    private static LibraryManager libraryManager;

    protected static LibraryManager getLibraryManager() {
        if (libraryManager == null) {
            libraryManager = new LibraryManager(getModelManager());
            libraryManager.getLibrarySourceLoader().registerProvider(new TestLibrarySourceProvider());
            libraryManager.getLibrarySourceLoader().registerProvider(new FhirLibrarySourceProvider());
        }

        return libraryManager;
    }

    @BeforeClass
    public void beforeEachTestMethod() throws JAXBException, IOException, UcumException {
        String fileName = this.getClass().getSimpleName();
        UcumService ucumService = new UcumEssenceService(
                UcumEssenceService.class.getResourceAsStream("/ucum-essence.xml"));
        File cqlFile = new File(URLDecoder.decode(this.getClass().getResource(fileName + ".cql").getFile(), "UTF-8"));

        ArrayList<CqlTranslator.Options> options = new ArrayList<>();
        options.add(CqlTranslator.Options.EnableDateRangeOptimization);
        options.add(CqlTranslator.Options.EnableAnnotations);
        options.add(CqlTranslator.Options.EnableLocators);
        cqlTranslator = CqlTranslator.fromFile(cqlFile, getModelManager(), getLibraryManager(), ucumService,
                options.toArray(new CqlTranslator.Options[options.size()]));

    }
}
