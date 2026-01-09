package org.opencds.cqf.cql.ls.server.manager;

import static kotlinx.io.files.PathsKt.Path;

import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.cqframework.cql.cql2elm.CqlCompilerOptions;
import org.cqframework.cql.cql2elm.CqlTranslatorOptions;
import org.cqframework.cql.cql2elm.LibraryBuilder.SignatureLevel;
import org.eclipse.lsp4j.FileEvent;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompilerOptionsManager {

    private static final Logger log = LoggerFactory.getLogger(CompilerOptionsManager.class);

    private ContentService contentService;

    public CompilerOptionsManager(ContentService contentService) {
        this.contentService = contentService;
    }

    private final Map<URI, CqlCompilerOptions> cachedOptions = new HashMap<>();

    public CqlCompilerOptions getOptions(URI uri) {
        URI root = Uris.getHead(uri);
        return cachedOptions.computeIfAbsent(root, this::readOptions);
    }

    protected void clearOptions(URI uri) {
        URI root = Uris.getHead(uri);
        this.cachedOptions.remove(root);
    }

    protected CqlCompilerOptions readOptions(URI rootUri) {

        CqlCompilerOptions options = null;

        var optionsUri = Uris.addPath(rootUri, "/cql-options.json");
        InputStream input = contentService.read(optionsUri);

        if (input != null) {
            // TODO: Why is this using fromFile and not fromSource?
            options = CqlTranslatorOptions.fromFile(Path("/cql-options.json")).getCqlCompilerOptions();
        } else {
            log.info(String.format("%s not found, using default options", optionsUri.toString()));
            options = CqlTranslatorOptions.defaultOptions().getCqlCompilerOptions();
        }

        // For the purposes of debugging and authoring support, always add detailed
        // translation information.
        return options.withOptions(
                        CqlCompilerOptions.Options.EnableLocators,
                        CqlCompilerOptions.Options.EnableResultTypes,
                        CqlCompilerOptions.Options.EnableAnnotations)
                .withSignatureLevel(SignatureLevel.All);
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onMessageEvent(DidChangeWatchedFilesEvent event) {
        for (FileEvent e : event.params().getChanges()) {
            if (e.getUri().endsWith("cql-options.json")) {
                this.clearOptions(Uris.parseOrNull(e.getUri()));
            }
        }
    }
}
