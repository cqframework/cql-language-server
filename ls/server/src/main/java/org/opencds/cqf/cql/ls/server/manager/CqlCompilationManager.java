package org.opencds.cqf.cql.ls.server.manager;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.cqframework.cql.cql2elm.CqlCompiler;
import org.cqframework.cql.cql2elm.LibraryManager;
import org.cqframework.cql.cql2elm.ModelManager;
import org.cqframework.cql.cql2elm.model.Model;
import org.cqframework.cql.cql2elm.quick.FhirLibrarySourceProvider;
import org.fhir.ucum.UcumEssenceService;
import org.fhir.ucum.UcumService;
import org.hl7.cql.model.ModelIdentifier;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.core.utility.Converters;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.provider.ContentServiceModelInfoProvider;
import org.opencds.cqf.cql.ls.server.provider.ContentServiceSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CqlCompilationManager {
    private static final Logger log = LoggerFactory.getLogger(CqlCompilationManager.class);

    private final Map<ModelIdentifier, Model> globalCache;
    private final ContentService contentService;
    private static UcumService ucumService = null;
    private final CompilerOptionsManager compilerOptionsManager;

    private final IgContextManager igContextManager;

    static {
        try {
            ucumService = new UcumEssenceService(UcumEssenceService.class.getResourceAsStream("/ucum-essence.xml"));
        } catch (Exception e) {
            log.warn("error initializing UcumService", e);
        }
    }

    public CqlCompilationManager(
            ContentService contentService,
            CompilerOptionsManager compilerOptionsManager,
            IgContextManager igContextManager) {
        this.globalCache = new HashMap<>();
        this.contentService = contentService;
        this.compilerOptionsManager = compilerOptionsManager;
        this.igContextManager = igContextManager;
    }

    private synchronized IgContextManager getIgContextManager() {
        return this.igContextManager;
    }

    public CqlCompiler compile(URI uri) {
        InputStream input = contentService.read(uri);
        if (input == null) {
            return null;
        }

        return this.compile(uri, input);
    }

    public CqlCompiler compile(URI uri, InputStream stream) {
        ModelManager modelManager = this.createModelManager();

        LibraryManager libraryManager = this.createLibraryManager(Uris.getHead(uri), modelManager);

        try {
            CqlCompiler compiler = new CqlCompiler(null,null,libraryManager);
            compiler.run(Converters.inputStreamToString(stream));
            return compiler;
        } catch (IOException e) {
            throw new IllegalArgumentException(String.format("error creating compiler for uri: %s", uri.toString()), e);
        }
    }

    private ModelManager createModelManager() {
        return new ModelManager(this.globalCache);
    }

    private LibraryManager createLibraryManager(URI root, ModelManager modelManager) {
        // TODO: Build a manager similar CompilerOptionsManager to support reacting to modelInfo file changes
        modelManager
                .getModelInfoLoader()
                .registerModelInfoProvider(new ContentServiceModelInfoProvider(root, this.contentService));
        LibraryManager libraryManager = new LibraryManager(modelManager, this.compilerOptionsManager.getOptions(root));
        libraryManager
                .getLibrarySourceLoader()
                .registerProvider(new ContentServiceSourceProvider(root, this.contentService));
        libraryManager.getLibrarySourceLoader().registerProvider(new FhirLibrarySourceProvider());
        getIgContextManager().setupLibraryManager(root, libraryManager);
        return libraryManager;
    }
}
