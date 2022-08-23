package org.opencds.cqf.cql.ls.core.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.net.URI;
import org.junit.jupiter.api.Test;

public class UrisTest {

    @Test
    public void encodedUriGetHead() {
        URI uri = URI.create("file:///d%3A/src/test.cql");
        URI root = Uris.getHead(uri);
        assertEquals("file:///d%3A/src", root.toString());
        assertEquals("test.cql", root.relativize(uri).toString());
    }

    @Test
    public void unencodedUriGetHead() {
        URI uri = URI.create("file:///home/src/test.cql");
        URI root = Uris.getHead(uri);
        assertEquals("file:///home/src", root.toString());
        assertEquals("test.cql", root.relativize(uri).toString());


        uri = URI.create("https://hl7.org/fhir/test.cql");
        root = Uris.getHead(uri);
        assertEquals("https://hl7.org/fhir", root.toString());
        assertEquals("test.cql", root.relativize(uri).toString());
    }

    @Test
    public void uriAddPath() {
        // With trailing slash
        URI root = URI.create("file:///d%3A/src/");
        URI uri = Uris.addPath(root, "test.cql");
        assertEquals("file:///d%3A/src/test.cql", uri.toString());

        uri = Uris.addPath(root, "/test.cql");
        assertEquals("file:///d%3A/src/test.cql", uri.toString());

        // Unencoded
        root = URI.create("file:///home/src");
        uri = Uris.addPath(root, "test.cql");
        assertEquals("file:///home/src/test.cql", uri.toString());

        // With Authority
        root = URI.create("http://localhost:8080/home/src");
        uri = Uris.addPath(root, "test.cql");
        assertEquals("http://localhost:8080/home/src/test.cql", uri.toString());
    }

    @Test
    public void uriWithPath() {
        URI initial = URI.create("file:///d%3A/src/");
        URI uri = Uris.withPath(initial, "/home/src/test.cql");
        assertEquals("file:///home/src/test.cql", uri.toString());

        uri = Uris.withPath(initial, "home/src/test.cql");
        assertEquals("file:///home/src/test.cql", uri.toString());

        // With authority
        initial = URI.create("https://hl7.org/fhir");
        uri = Uris.withPath(initial, "/fhir/test.cql");
        assertEquals("https://hl7.org/fhir/test.cql", uri.toString());

        uri = Uris.withPath(initial, "fhir/test.cql");
        assertEquals("https://hl7.org/fhir/test.cql", uri.toString());

        // With trailing slash on URI
        initial = URI.create("http://locahost:8080/fhir/");
        uri = Uris.withPath(initial, "/fhir/test.cql");
        assertEquals("http://locahost:8080/fhir/test.cql", uri.toString());

        uri = Uris.withPath(initial, "fhir/test.cql");
        assertEquals("http://locahost:8080/fhir/test.cql", uri.toString());
    }
}
