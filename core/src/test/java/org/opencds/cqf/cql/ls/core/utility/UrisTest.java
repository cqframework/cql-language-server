package org.opencds.cqf.cql.ls.core.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class UrisTest {

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void encodedUriGetHead() {
        URI uri = Uris.parseOrNull("file:///d%3A/src/test.cql");
        URI root = Uris.getHead(uri);
        assertEquals("file:///d%3A/src", root.toString());
        assertEquals("test.cql", root.relativize(uri).toString());
    }

    @Test
    @EnabledOnOs({OS.WINDOWS})
    void encodedUriGetHeadWindows() {
        URI uri = Uris.parseOrNull("file:///d%3A/src/test.cql");
        URI root = Uris.getHead(uri);
        assertEquals("file:///d:/src", root.toString());
        assertEquals("test.cql", root.relativize(uri).toString());
    }

    @Test
    void unencodedUriGetHeadHttps() {
        URI uri = Uris.parseOrNull("https://hl7.org/fhir/test.cql");
        URI root = Uris.getHead(uri);
        assertEquals("https://hl7.org/fhir", root.toString());
        assertEquals("test.cql", root.relativize(uri).toString());
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void unencodedUriGetHeadFile() {
        URI uri = Uris.parseOrNull("file:///home/src/test.cql");
        URI root = Uris.getHead(uri);
        assertEquals("file:///home/src", root.toString());
        assertEquals("test.cql", root.relativize(uri).toString());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void unencodedUriGetHeadFileWindows() {
        URI uri = Uris.parseOrNull("file:///home/src/test.cql");
        URI root = Uris.getHead(uri);
        assertEquals("file:////home/src", root.toString());
        assertEquals("test.cql", root.relativize(uri).toString());
    }

    @Test
    void uriAddPathHttp() {
        URI root = Uris.parseOrNull("http://localhost:8080/home/src");
        URI uri = Uris.addPath(root, "test.cql");
        assertEquals("http://localhost:8080/home/src/test.cql", uri.toString());
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void uriAddPath() {
        // With trailing slash
        URI root = Uris.parseOrNull("file:///d%3A/src/");
        URI uri = Uris.addPath(root, "test.cql");
        assertEquals("file:///d%3A/src/test.cql", uri.toString());

        uri = Uris.addPath(root, "/test.cql");
        assertEquals("file:///d%3A/src/test.cql", uri.toString());

        // Unencoded
        root = Uris.parseOrNull("file:///home/src");
        uri = Uris.addPath(root, "test.cql");
        assertEquals("file:///home/src/test.cql", uri.toString());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void uriAddPathWindows() {
        // With trailing slash
        URI root = Uris.parseOrNull("file:///d%3A/src/");
        URI uri = Uris.addPath(root, "test.cql");
        assertEquals("file:///d:/src/test.cql", uri.toString());

        uri = Uris.addPath(root, "/test.cql");
        assertEquals("file:///d:/src/test.cql", uri.toString());

        // UNC path
        root = Uris.parseOrNull("file:///home/src");
        uri = Uris.addPath(root, "test.cql");
        assertEquals("file:////home/src/test.cql", uri.toString());
    }


    @Test
    void uriWithPath() {
        URI initial = Uris.parseOrNull("file:///d%3A/src/");
        URI uri = Uris.withPath(initial, "/home/src/test.cql");
        assertEquals("file:///home/src/test.cql", uri.toString());

        uri = Uris.withPath(initial, "home/src/test.cql");
        assertEquals("file:///home/src/test.cql", uri.toString());

        // With authority
        initial = Uris.parseOrNull("https://hl7.org/fhir");
        uri = Uris.withPath(initial, "/fhir/test.cql");
        assertEquals("https://hl7.org/fhir/test.cql", uri.toString());

        uri = Uris.withPath(initial, "fhir/test.cql");
        assertEquals("https://hl7.org/fhir/test.cql", uri.toString());

        // With trailing slash on URI
        initial = Uris.parseOrNull("http://locahost:8080/fhir/");
        uri = Uris.withPath(initial, "/fhir/test.cql");
        assertEquals("http://locahost:8080/fhir/test.cql", uri.toString());

        uri = Uris.withPath(initial, "fhir/test.cql");
        assertEquals("http://locahost:8080/fhir/test.cql", uri.toString());
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void toClientStringUnix() {
        URI initial = Uris.parseOrNull("file:////d%3A/src/");
        String client = Uris.toClientUri(initial);
        assertEquals("file:////d%3A/src/", client);
    }

    @Test
    @EnabledOnOs({OS.WINDOWS})
    void toClientStringWindows() {
        URI initial = Uris.parseOrNull("file://d%3A/src/");
        String client = Uris.toClientUri(initial);
        assertEquals("file:/d:/src", client);

        initial = Uris.parseOrNull("file:////d%3A/src/");
        client = Uris.toClientUri(initial);
        assertEquals("file:/d:/src", client);

        initial = Uris.parseOrNull("file:/d%3A/src/");
        client = Uris.toClientUri(initial);
        assertEquals("file:/d:/src", client);
    }
}
