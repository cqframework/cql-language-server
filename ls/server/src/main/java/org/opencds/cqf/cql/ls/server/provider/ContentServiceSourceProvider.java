package org.opencds.cqf.cql.ls.server.provider;

import static org.opencds.cqf.cql.ls.core.utility.Converters.inputStreamToSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import kotlinx.io.Source;
import org.cqframework.cql.cql2elm.LibrarySourceProvider;
import org.hl7.elm.r1.VersionedIdentifier;
import org.opencds.cqf.cql.ls.core.ContentService;

public class ContentServiceSourceProvider implements LibrarySourceProvider {

    private final ContentService contentService;
    private final URI root;

    public ContentServiceSourceProvider(URI root, ContentService contentService) {
        this.contentService = contentService;
        this.root = root;
    }

    public Source getLibrarySource(VersionedIdentifier libraryIdentifier) {
        try {
            InputStream is = this.contentService.read(this.root, libraryIdentifier);
            if (is != null) {
                return inputStreamToSource(is));
            }
            return null;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
