package org.opencds.cqf.cql.ls.server;

import java.io.InputStream;
import java.net.URI;
import java.util.List;

import org.hl7.elm.r1.VersionedIdentifier;
import org.opencds.cqf.cql.ls.core.ContentService;

public class TestContentService implements ContentService {

    @Override
    public List<URI> locate(VersionedIdentifier libraryIdentifier) {
        return null;
    }

    public InputStream read(URI uri) {
        return null;
    }
}
