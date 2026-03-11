package org.opencds.cqf.cql.ls.server.provider;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import kotlinx.io.Source;
import org.cqframework.cql.cql2elm.LibrarySourceProvider;
import org.hl7.elm.r1.VersionedIdentifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.server.service.TestContentService;

class ContentServiceSourceProviderTest {

    private static ContentServiceSourceProvider provider;

    @BeforeAll
    static void beforeAll() {
        ContentService cs = new TestContentService();
        // Root is arbitrary for TestContentService; it resolves by library name from classpath.
        URI root = URI.create("file:///workspace/");
        provider = new ContentServiceSourceProvider(root, cs);
    }

    @Test
    void getLibrarySource_knownLibrary_returnsSource() {
        // "One" resolves to /org/opencds/cqf/cql/ls/server/One.cql on classpath.
        VersionedIdentifier id = new VersionedIdentifier().withId("One");

        Source source = provider.getLibrarySource(id);

        assertNotNull(source);
    }

    @Test
    void getLibrarySource_unknownLibrary_returnsNull() {
        VersionedIdentifier id = new VersionedIdentifier().withId("DoesNotExistLibrary");

        Source source = provider.getLibrarySource(id);

        assertNull(source);
    }

    @Test
    void getLibrarySource_implementsLibrarySourceProvider() {
        // Verify the class satisfies the LibrarySourceProvider contract expected by the compiler.
        assertNotNull((LibrarySourceProvider) provider);
    }
}
