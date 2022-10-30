package org.opencds.cqf.cql.ls.server.manager;

import org.cqframework.cql.cql2elm.*;
import org.cqframework.fhir.npm.ILibraryReader;
import org.cqframework.fhir.npm.NpmLibrarySourceProvider;
import org.cqframework.fhir.utilities.IGContext;
import org.eclipse.lsp4j.FileEvent;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.hl7.cql.model.NamespaceInfo;
import org.opencds.cqf.cql.evaluator.fhir.npm.LoggerAdapter;
import org.opencds.cqf.cql.evaluator.fhir.npm.NpmProcessor;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public class IgContextManager {
    private static final Logger log = LoggerFactory.getLogger(IgContextManager.class);

    private ContentService contentService;

    public IgContextManager(ContentService contentService) {
        this.contentService = contentService;
    }

    private final Map<URI, NpmProcessor> cachedContext = new HashMap<>();

    public NpmProcessor getContext(URI uri) {
        URI root = Uris.getHead(uri);
        return cachedContext.computeIfAbsent(root, this::readContext);
    }

    protected void clearContext(URI uri) {
        URI root = Uris.getHead(uri);
        this.cachedContext.remove(root);
    }

    protected NpmProcessor readContext(URI rootUri) {
        IGContext igContext = findIgContext(rootUri);
        if (igContext != null) {
            return new NpmProcessor(igContext);
        }

        return null;
    }

    public void setupLibraryManager(URI uri, LibraryManager libraryManager) {
        NpmProcessor npmProcessor = getContext(uri);
        if (npmProcessor != null) {
            libraryManager.getNamespaceManager().addNamespace(npmProcessor.getIgNamespace());
            ILibraryReader reader = new org.cqframework.fhir.npm.LibraryLoader(npmProcessor.getIgContext().getFhirVersion());
            libraryManager.getLibrarySourceLoader().registerProvider(new NpmLibrarySourceProvider(npmProcessor.getPackageManager().getNpmList(), reader, new LoggerAdapter(log)));
            for (NamespaceInfo ni : npmProcessor.getNamespaces()) {
                libraryManager.getNamespaceManager().addNamespace(ni);
            }
        }
    }

    /**
     * Searches for an ig.ini file in the parent and grandparent of the given uri
     * @param uri
     * @return
     */
    protected IGContext findIgContext(URI uri) {
        // TODO: Support igs that don't have an ini by just looking for an implementation guide resource
        log.info("Searching for ini file in %s", uri.toString());
        URI current = uri;
        for (int i = 0; i < 2; i++) {
            URI parent = Uris.getHead(current);
            if (!parent.equals(current)) {
                current = parent;
                URI igIniPath = Uris.addPath(parent, "/ig.ini");
                log.info("Attempting to read ini from path %s", igIniPath.toString());
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
