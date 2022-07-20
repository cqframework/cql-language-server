package org.opencds.cqf.cql.ls.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

import org.apache.commons.lang3.NotImplementedException;
import org.hl7.elm.r1.VersionedIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface ContentService {
    static final Logger log = LoggerFactory.getLogger(ContentService.class);

    default URI locate(VersionedIdentifier libraryIdentifier) {
        throw new NotImplementedException();
    }

    default InputStream read(VersionedIdentifier identifier) {
        return read(locate(identifier));
    }

    default InputStream read(URI uri) {
        if (uri == null) {
            return null;
        }

        try {
            return uri.toURL().openStream();
        }
        catch(IOException e) {
            log.warn(String.format("error opening stream for: %s", uri.toString()), e);
            return null;
        }
    }

    default void write(URI uri, String content) {
        throw new NotImplementedException();
    }
}
