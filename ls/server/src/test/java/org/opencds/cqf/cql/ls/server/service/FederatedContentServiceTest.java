package org.opencds.cqf.cql.ls.server.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.hl7.elm.r1.VersionedIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencds.cqf.cql.ls.core.ContentService;

class FederatedContentServiceTest {

    private ActiveContentService activeService;
    private ContentService fileService;
    private FederatedContentService fedService;

    private static final URI ROOT = URI.create("file:///workspace/");
    private static final URI ACTIVE_URI = URI.create("file:///workspace/One.cql");
    private static final URI FILE_URI = URI.create("file:///workspace/lib/One.cql");

    @BeforeEach
    void setUp() {
        activeService = mock(ActiveContentService.class);
        fileService = mock(ContentService.class);
        fedService = new FederatedContentService(activeService, fileService);
    }

    // -----------------------------------------------------------------------
    // locate — merges results from both services
    // -----------------------------------------------------------------------

    @Test
    void locate_mergesActiveAndFileResults() {
        VersionedIdentifier id = new VersionedIdentifier().withId("One").withVersion("1.0.0");
        Set<URI> activeResult = new HashSet<>(Collections.singletonList(ACTIVE_URI));
        Set<URI> fileResult = new HashSet<>(Collections.singletonList(FILE_URI));

        when(activeService.locate(ROOT, id)).thenReturn(activeResult);
        when(fileService.locate(ROOT, id)).thenReturn(fileResult);

        Set<URI> result = fedService.locate(ROOT, id);

        assertTrue(result.contains(ACTIVE_URI));
        assertTrue(result.contains(FILE_URI));
    }

    @Test
    void locate_emptyFromBoth_returnsEmptySet() {
        VersionedIdentifier id = new VersionedIdentifier().withId("Unknown");
        when(activeService.locate(ROOT, id)).thenReturn(new HashSet<>());
        when(fileService.locate(ROOT, id)).thenReturn(new HashSet<>());

        Set<URI> result = fedService.locate(ROOT, id);

        assertTrue(result.isEmpty());
    }

    // -----------------------------------------------------------------------
    // read — prefers active content when URI is in active set
    // -----------------------------------------------------------------------

    @Test
    void read_uriInActiveSet_returnsActiveStream() {
        InputStream expected = new ByteArrayInputStream("active content".getBytes());
        when(activeService.activeUris()).thenReturn(Collections.singleton(ACTIVE_URI));
        when(activeService.read(ACTIVE_URI)).thenReturn(expected);

        InputStream result = fedService.read(ACTIVE_URI);

        assertSame(expected, result);
    }

    @Test
    void read_uriNotInActiveSet_fallsBackToFileService() {
        InputStream expected = new ByteArrayInputStream("file content".getBytes());
        when(activeService.activeUris()).thenReturn(Collections.emptySet());
        when(fileService.read(FILE_URI)).thenReturn(expected);

        InputStream result = fedService.read(FILE_URI);

        assertSame(expected, result);
    }
}
