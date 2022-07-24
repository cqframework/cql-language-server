package org.opencds.cqf.cql.ls.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.NotImplementedException;
import org.hl7.elm.r1.VersionedIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface ContentService {
    static final Logger log = LoggerFactory.getLogger(ContentService.class);


    default List<URI> locate(VersionedIdentifier identifier) {
        Objects.requireNonNull(identifier);
        throw new NotImplementedException();
    }

    default InputStream read(VersionedIdentifier identifier) {
        Objects.requireNonNull(identifier);

        List<URI> locations = locate(identifier);
        if (locations.isEmpty()) {
            return null;
        }

        if (locations.size() > 1) {
            throw new IllegalStateException(String.format(
                    "more than one file was found for library: %s version: %s in the current workspace.",
                    identifier.getId(), identifier.getVersion()));
        }
        return read(locations.get(0));
    }

    default InputStream read(URI uri) {
        Objects.requireNonNull(uri);

        try {
            return uri.toURL().openStream();
        } catch (IOException e) {
            log.warn(String.format("error opening stream for: %s", uri.toString()), e);
            return null;
        }
    }
}
