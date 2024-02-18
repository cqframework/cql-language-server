package org.opencds.cqf.cql.ls.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.NotImplementedException;
import org.hl7.elm.r1.VersionedIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface ContentService {
    static final Logger log = LoggerFactory.getLogger(ContentService.class);

    default Set<URI> locate(URI root, VersionedIdentifier identifier) {
        throw new NotImplementedException();
    }

    default InputStream read(URI root, VersionedIdentifier identifier) {
        Objects.requireNonNull(identifier);

        Set<URI> locations = locate(root, identifier);
        if (locations.isEmpty()) {
            return null;
        }

        if (locations.size() > 1) {
            String allLocations =
                    String.join("%n", locations.stream().map(String::valueOf).collect(Collectors.toList()));
            throw new IllegalStateException(String.format(
                    "more than one location was found for library: %s version: %s in the current workspace:%n%s",
                    identifier.getId(), identifier.getVersion(), allLocations));
        }
        return read(locations.iterator().next());
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
