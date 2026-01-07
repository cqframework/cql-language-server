package org.opencds.cqf.cql.ls.server.provider;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Set;
import org.hl7.elm.r1.VersionedIdentifier;
import org.junit.jupiter.api.Test;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.core.utility.Uris;

public class ContentServiceProviderTest {

    @Test
    void should_throwException_when_gettingLibrary() throws Exception {
        VersionedIdentifier versionedIdentifier = new VersionedIdentifier();
        versionedIdentifier.withVersion("1.0.0");

        ContentServiceSourceProvider contentServiceSourceProvider = new ContentServiceSourceProvider(
                Uris.parseOrNull("/provider/content/sample-library-1.0.0.json"), new ContentService() {
                    @Override
                    public Set<URI> locate(URI root, VersionedIdentifier identifier) {
                        throw new UncheckedIOException(new IOException());
                    }
                });

        assertThrows(RuntimeException.class, () -> contentServiceSourceProvider.getLibrarySource(versionedIdentifier));
    }
}
