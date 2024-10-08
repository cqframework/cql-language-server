package org.opencds.cqf.cql.ls.server.manager;

import java.io.InputStream;
import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.cqframework.cql.cql2elm.LibraryManager;
import org.cqframework.fhir.npm.ILibraryReader;
import org.cqframework.fhir.npm.NpmLibrarySourceProvider;
import org.cqframework.fhir.npm.NpmModelInfoProvider;
import org.cqframework.fhir.npm.NpmProcessor;
import org.cqframework.fhir.utilities.IGContext;
import org.cqframework.fhir.utilities.LoggerAdapter;
import org.eclipse.lsp4j.FileEvent;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IgContextManager {
    private static final Logger log = LoggerFactory.getLogger(IgContextManager.class);

    private ContentService contentService;

    public IgContextManager(ContentService contentService) {
        this.contentService = contentService;
    }

    private final Map<URI, Optional<NpmProcessor>> cachedContext = new ConcurrentHashMap<>();

    public NpmProcessor getContext(URI uri) {
        URI root = Uris.getHead(uri);
        return cachedContext.computeIfAbsent(root, this::readContext).orElse(null);
    }

    protected void clearContext(URI uri) {
        URI root = Uris.getHead(uri);
        this.cachedContext.remove(root);
    }

    protected Optional<NpmProcessor> readContext(URI rootUri) {
        IGContext igContext = findIgContext(rootUri);
        if (igContext != null) {
            return Optional.of(new NpmProcessor(igContext));
        }

        return Optional.empty();
    }

    public synchronized void setupLibraryManager(URI uri, LibraryManager libraryManager) {
        NpmProcessor npmProcessor = getContext(uri);
        if (npmProcessor != null) {
            var namespaceManager = libraryManager.getNamespaceManager();
            namespaceManager.ensureNamespaceRegistered(npmProcessor.getIgNamespace());
            ILibraryReader reader = new org.cqframework.fhir.npm.LibraryLoader(
                    npmProcessor.getIgContext().getFhirVersion());
            LoggerAdapter adapter = new LoggerAdapter(log);
            libraryManager
                    .getLibrarySourceLoader()
                    .registerProvider(new NpmLibrarySourceProvider(
                            npmProcessor.getPackageManager().getNpmList(), reader, adapter));
            libraryManager
                    .getModelManager()
                    .getModelInfoLoader()
                    .registerModelInfoProvider(new NpmModelInfoProvider(
                            npmProcessor.getPackageManager().getNpmList(), reader, adapter));

            // TODO: This is a workaround for: a) multiple packages with the same package id will be in the dependency
            // list, and b) there are packages with different package ids but the same base canonical (e.g.
            // fhir.r4.examples has the same base canonical as fhir.r4)
            // NOTE: Using ensureNamespaceRegistered works around a but not b
            // NOTE: This logic is also used in org.opencds.cqf.fhir.cql.Engines.buildEnvironment()
            Set<String> keys = new HashSet<String>();
            Set<String> uris = new HashSet<String>();
            for (var n : npmProcessor.getNamespaces()) {
                if (!keys.contains(n.getName()) && !uris.contains(n.getUri())) {
                    libraryManager.getNamespaceManager().addNamespace(n);
                    keys.add(n.getName());
                    uris.add(n.getUri());
                }
            }
        }
    }

    /**
     * Searches for an ig.ini file in the parent and grandparent of the given uri
     *
     * @param uri
     * @return
     */
    protected IGContext findIgContext(URI uri) {
        // TODO: Support igs that don't have an ini by just looking for an implementation guide
        // resource
        log.info("Searching for ini file in {}", uri);
        URI current = uri;
        for (int i = 0; i < 2; i++) {
            URI parent = Uris.getHead(current);
            if (!parent.equals(current)) {
                current = parent;
                URI igIniPath = Uris.addPath(parent, "/ig.ini");
                log.info("Attempting to read ini from path {}", igIniPath);
                InputStream input = contentService.read(igIniPath);
                if (input != null) {
                    log.info("Initializing ig from ini...");
                    IGContext igContext = new IGContext(new LoggerAdapter(log));
                    igContext.initializeFromIni(igIniPath.getSchemeSpecificPart());
                    log.info("IGContext Initialized.");
                    return igContext;
                }
            }
        }
        return null;
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onMessageEvent(DidChangeWatchedFilesEvent event) {
        for (FileEvent e : event.params().getChanges()) {
            if (e.getUri().endsWith("ig.ini")) {
                this.clearContext(Uris.parseOrNull(e.getUri()));
            }
        }
    }
}
