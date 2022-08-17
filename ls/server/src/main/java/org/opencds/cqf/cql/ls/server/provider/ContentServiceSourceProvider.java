package org.opencds.cqf.cql.ls.server.provider;

import java.io.InputStream;
import java.net.URI;
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

    @Override
    public InputStream getLibrarySource(VersionedIdentifier libraryIdentifier) {
        return this.contentService.read(this.root, libraryIdentifier);
    }
}
