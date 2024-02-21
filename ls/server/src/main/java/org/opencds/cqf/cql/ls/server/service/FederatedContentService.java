package org.opencds.cqf.cql.ls.server.service;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.InputStream;
import java.net.URI;
import java.util.Set;
import org.hl7.elm.r1.VersionedIdentifier;
import org.opencds.cqf.cql.ls.core.ContentService;

public class FederatedContentService implements ContentService {

    private ActiveContentService activeContentService;
    private ContentService fileContentService;

    public FederatedContentService(ActiveContentService activeContentService, ContentService fileContentService) {
        this.activeContentService = activeContentService;
        this.fileContentService = fileContentService;
    }

    @Override
    public Set<URI> locate(URI root, VersionedIdentifier identifier) {
        checkNotNull(root);
        checkNotNull(identifier);

        Set<URI> locations = this.activeContentService.locate(root, identifier);

        locations.addAll(this.fileContentService.locate(root, identifier));

        return locations;
    }

    @Override
    public InputStream read(URI uri) {
        checkNotNull(uri);

        if (this.activeContentService.activeUris().contains(uri)) {
            return this.activeContentService.read(uri);
        }

        return this.fileContentService.read(uri);
    }
}
