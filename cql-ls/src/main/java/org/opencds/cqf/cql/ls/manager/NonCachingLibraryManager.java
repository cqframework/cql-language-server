package org.opencds.cqf.cql.ls.manager;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.cqframework.cql.cql2elm.*;
import org.cqframework.cql.cql2elm.model.TranslatedLibrary;
import org.hl7.elm.r1.VersionedIdentifier;

public class NonCachingLibraryManager extends LibraryManager {

    private ModelManager modelManager;
    private final CopiedLibrarySourceLoader librarySourceLoader;
    
    public NonCachingLibraryManager(ModelManager modelManager) {
        super(modelManager);
        
        this.modelManager = modelManager;
        this.librarySourceLoader = new CopiedLibrarySourceLoader();
    }

    @Override
    public LibrarySourceLoader getLibrarySourceLoader() {
        return librarySourceLoader;
    }

    @Override
    public boolean canResolveLibrary(VersionedIdentifier libraryIdentifier) {
        if (libraryIdentifier == null) {
            throw new IllegalArgumentException("libraryIdentifier is null.");
        }

        if (libraryIdentifier.getId() == null || libraryIdentifier.getId().equals("")) {
            throw new IllegalArgumentException("libraryIdentifier Id is null");
        }

        InputStream librarySource = null;
        try {
            librarySource = librarySourceLoader.getLibrarySource(libraryIdentifier);
        }
        catch (Exception e) {
            throw new CqlTranslatorIncludeException(e.getMessage(), libraryIdentifier.getSystem(), libraryIdentifier.getId(), libraryIdentifier.getVersion(), e);
        }

        return librarySource != null;
    }

    @Override
    public TranslatedLibrary resolveLibrary(VersionedIdentifier libraryIdentifier, CqlTranslatorOptions options, List<CqlTranslatorException> errors) {
        if (libraryIdentifier == null) {
            throw new IllegalArgumentException("libraryIdentifier is null.");
        }

        if (libraryIdentifier.getId() == null || libraryIdentifier.getId().equals("")) {
            throw new IllegalArgumentException("libraryIdentifier Id is null");
        }

        return translateLibrary(libraryIdentifier, options, errors);
    }

    private TranslatedLibrary translateLibrary(VersionedIdentifier libraryIdentifier, 
        CqlTranslatorOptions options, List<CqlTranslatorException> errors) {
        InputStream librarySource = null;
        try {
            librarySource = librarySourceLoader.getLibrarySource(libraryIdentifier);
        }
        catch (Exception e) {
            throw new CqlTranslatorIncludeException(e.getMessage(), libraryIdentifier.getSystem(), libraryIdentifier.getId(), libraryIdentifier.getVersion(), e);
        }

        if (librarySource == null) {
            throw new CqlTranslatorIncludeException(String.format("Could not load source for library %s, version %s.",
                    libraryIdentifier.getId(), libraryIdentifier.getVersion()), libraryIdentifier.getSystem(), libraryIdentifier.getId(), libraryIdentifier.getVersion());
        }

        try {
            CqlTranslator translator = CqlTranslator.fromStream(librarySource, modelManager, this, null, options);

            if (errors != null) {
                errors.addAll(translator.getExceptions());
            }

            TranslatedLibrary result = translator.getTranslatedLibrary();
            if (libraryIdentifier.getVersion() != null && !libraryIdentifier.getVersion().equals(result.getIdentifier().getVersion())) {
                throw new CqlTranslatorIncludeException(String.format("Library %s was included as version %s, but version %s of the library was found.",
                        libraryIdentifier.getId(), libraryIdentifier.getVersion(), result.getIdentifier().getVersion()),
                        libraryIdentifier.getSystem(), libraryIdentifier.getId(), libraryIdentifier.getVersion());
            }

            return result;
        } catch (IOException e) {
            throw new CqlTranslatorIncludeException(String.format("Errors occurred translating library %s, version %s.",
                    libraryIdentifier.getId(), libraryIdentifier.getVersion()), libraryIdentifier.getSystem(), libraryIdentifier.getId(), libraryIdentifier.getVersion(), e);
        }
    }
}

class CopiedLibrarySourceLoader implements LibrarySourceLoader, NamespaceAware {
    private final List<LibrarySourceProvider> PROVIDERS = new ArrayList<>();

  @Override
    public void registerProvider(LibrarySourceProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider is null.");
        }

        if (provider instanceof NamespaceAware) {
            ((NamespaceAware)provider).setNamespaceManager(namespaceManager);
        }

        PROVIDERS.add(provider);
    }

  @Override
    public void clearProviders() {
        PROVIDERS.clear();
    }

  @Override
    public InputStream getLibrarySource(VersionedIdentifier libraryIdentifier) {
        if (libraryIdentifier == null) {
            throw new IllegalArgumentException("libraryIdentifier is null.");
        }

        if (libraryIdentifier.getId() == null || libraryIdentifier.getId().equals("")) {
            throw new IllegalArgumentException("libraryIdentifier Id is null.");
        }

        InputStream source = null;
        for (LibrarySourceProvider provider : PROVIDERS) {
            InputStream localSource = provider.getLibrarySource(libraryIdentifier);
            if (localSource != null) {
                source = localSource;
                break;
            }
        }

        if (source == null) {
            throw new IllegalArgumentException(String.format("Could not load source for library %s, version %s.",
                    libraryIdentifier.getId(), libraryIdentifier.getVersion()));
        }

        return source;
    }

    private NamespaceManager namespaceManager;

    @Override
    public void setNamespaceManager(NamespaceManager namespaceManager) {
        this.namespaceManager = namespaceManager;

        for (LibrarySourceProvider provider : PROVIDERS) {
            if (provider instanceof NamespaceAware) {
                ((NamespaceAware)provider).setNamespaceManager(namespaceManager);
            }
        }
    }
}
