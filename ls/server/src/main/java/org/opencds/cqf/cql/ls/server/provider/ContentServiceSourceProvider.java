package org.opencds.cqf.cql.ls.server.provider;

import kotlinx.io.Source;

import java.io.IOException;
import java.net.URI;
import org.cqframework.cql.cql2elm.LibrarySourceProvider;
import org.hl7.elm.r1.VersionedIdentifier;
import org.opencds.cqf.cql.ls.core.ContentService;

import static org.opencds.cqf.cql.ls.core.utility.Converters.inputStreamToSource;

public class ContentServiceSourceProvider implements LibrarySourceProvider {

    private final ContentService contentService;
    private final URI root;

    public ContentServiceSourceProvider(URI root, ContentService contentService) {
        this.contentService = contentService;
        this.root = root;
    }

    public Source getLibrarySource(VersionedIdentifier libraryIdentifier) {
        try {
            return inputStreamToSource(this.contentService.read(this.root, libraryIdentifier));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}