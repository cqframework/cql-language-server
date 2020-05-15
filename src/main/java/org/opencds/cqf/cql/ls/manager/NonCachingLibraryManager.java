package org.opencds.cqf.cql.ls.manager;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.cqframework.cql.cql2elm.CqlTranslator;
import org.cqframework.cql.cql2elm.CqlTranslatorException;
import org.cqframework.cql.cql2elm.CqlTranslatorIncludeException;
import org.cqframework.cql.cql2elm.CqlTranslatorOptions;
import org.cqframework.cql.cql2elm.LibraryManager;
import org.cqframework.cql.cql2elm.LibrarySourceLoader;
import org.cqframework.cql.cql2elm.LibrarySourceProvider;
import org.cqframework.cql.cql2elm.ModelManager;
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
            throw new CqlTranslatorIncludeException(String.format("Could not load source for library {}, version {}.",
                    libraryIdentifier.getId(), libraryIdentifier.getVersion()), libraryIdentifier.getSystem(), libraryIdentifier.getId(), libraryIdentifier.getVersion());
        }

        try {
            CqlTranslator translator = CqlTranslator.fromStream(librarySource, modelManager, this, null, options);

            if (errors != null) {
                errors.addAll(translator.getExceptions());
            }

            TranslatedLibrary result = translator.getTranslatedLibrary();
            if (libraryIdentifier.getVersion() != null && !libraryIdentifier.getVersion().equals(result.getIdentifier().getVersion())) {
                throw new CqlTranslatorIncludeException(String.format("Library {} was included as version {}, but version {} of the library was found.",
                        libraryIdentifier.getId(), libraryIdentifier.getVersion(), result.getIdentifier().getVersion()),
                        libraryIdentifier.getSystem(), libraryIdentifier.getId(), libraryIdentifier.getVersion());
            }

            return result;
        } catch (IOException e) {
            throw new CqlTranslatorIncludeException(String.format("Errors occurred translating library {}, version {}.",
                    libraryIdentifier.getId(), libraryIdentifier.getVersion()), libraryIdentifier.getSystem(), libraryIdentifier.getId(), libraryIdentifier.getVersion(), e);
        }
    }
}

class CopiedLibrarySourceLoader implements LibrarySourceLoader {
    private final List<LibrarySourceProvider> PROVIDERS = new ArrayList<>();

  @Override
    public void registerProvider(LibrarySourceProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider is null.");
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
            throw new IllegalArgumentException(String.format("Could not load source for library {}, version {}.",
                    libraryIdentifier.getId(), libraryIdentifier.getVersion()));
        }

        return source;
    }
}
