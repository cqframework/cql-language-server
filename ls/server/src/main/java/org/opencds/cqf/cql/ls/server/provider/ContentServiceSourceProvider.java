package org.opencds.cqf.cql.ls.server.provider;

import java.io.InputStream;

import org.cqframework.cql.cql2elm.LibrarySourceProvider;
import org.hl7.elm.r1.VersionedIdentifier;
import org.opencds.cqf.cql.ls.core.ContentService;

public class ContentServiceSourceProvider implements LibrarySourceProvider {

    private final ContentService contentService;

    public ContentServiceSourceProvider(ContentService contentService) {
        this.contentService = contentService;
    }

    @Override
    public InputStream getLibrarySource(VersionedIdentifier libraryIdentifier) {
      return this.contentService.read(libraryIdentifier);
    }
}