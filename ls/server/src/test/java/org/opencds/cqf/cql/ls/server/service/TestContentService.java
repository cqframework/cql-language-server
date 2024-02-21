package org.opencds.cqf.cql.ls.server.service;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Set;
import org.hl7.elm.r1.VersionedIdentifier;
import org.opencds.cqf.cql.ls.core.ContentService;

public class TestContentService implements ContentService {

    @Override
    public Set<URI> locate(URI root, VersionedIdentifier libraryIdentifier) {
        try {
            return Collections.singleton(
                    new URI("/org/opencds/cqf/cql/ls/server/" + libraryIdentifier.getId() + ".cql"));
        } catch (URISyntaxException e) {
            throw new RuntimeException(
                    String.format("error locating test contest for: %s", libraryIdentifier.toString()));
        }
    }

    public InputStream read(URI uri) {
        return TestContentService.class.getResourceAsStream(uri.toString());
    }
}
