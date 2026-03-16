package org.opencds.cqf.cql.ls.core.utility

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

class UrisTest {

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun encodedUriGetHead() {
        val uri = Uris.parseOrNull("file:///d%3A/src/test.cql")!!
        val root = Uris.getHead(uri)
        assertEquals("file:///d%3A/src", root.toString())
        assertEquals("test.cql", root.relativize(uri).toString())
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun encodedUriGetHeadWindows() {
        val uri = Uris.parseOrNull("file:///d%3A/src/test.cql")!!
        val root = Uris.getHead(uri)
        assertEquals("file:///d:/src", root.toString())
        assertEquals("test.cql", root.relativize(uri).toString())
    }

    @Test
    fun unencodedUriGetHeadHttps() {
        val uri = Uris.parseOrNull("https://hl7.org/fhir/test.cql")!!
        val root = Uris.getHead(uri)
        assertEquals("https://hl7.org/fhir", root.toString())
        assertEquals("test.cql", root.relativize(uri).toString())
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun unencodedUriGetHeadFile() {
        val uri = Uris.parseOrNull("file:///home/src/test.cql")!!
        val root = Uris.getHead(uri)
        assertEquals("file:///home/src", root.toString())
        assertEquals("test.cql", root.relativize(uri).toString())
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun unencodedUriGetHeadFileWindows() {
        val uri = Uris.parseOrNull("file:///home/src/test.cql")!!
        val root = Uris.getHead(uri)
        assertEquals("file:////home/src", root.toString())
        assertEquals("test.cql", root.relativize(uri).toString())
    }

    @Test
    fun uriAddPathHttp() {
        val root = Uris.parseOrNull("http://localhost:8080/home/src")!!
        val uri = Uris.addPath(root, "test.cql")
        assertEquals("http://localhost:8080/home/src/test.cql", uri.toString())
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun uriAddPath() {
        var root = Uris.parseOrNull("file:///d%3A/src/")!!
        var uri = Uris.addPath(root, "test.cql")
        assertEquals("file:///d%3A/src/test.cql", uri.toString())

        uri = Uris.addPath(root, "/test.cql")
        assertEquals("file:///d%3A/src/test.cql", uri.toString())

        root = Uris.parseOrNull("file:///home/src")!!
        uri = Uris.addPath(root, "test.cql")
        assertEquals("file:///home/src/test.cql", uri.toString())
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun uriAddPathWindows() {
        var root = Uris.parseOrNull("file:///d%3A/src/")!!
        var uri = Uris.addPath(root, "test.cql")
        assertEquals("file:///d:/src/test.cql", uri.toString())

        uri = Uris.addPath(root, "/test.cql")
        assertEquals("file:///d:/src/test.cql", uri.toString())

        root = Uris.parseOrNull("file:///home/src")!!
        uri = Uris.addPath(root, "test.cql")
        assertEquals("file:////home/src/test.cql", uri.toString())
    }

    @Test
    fun uriWithPath() {
        var initial = Uris.parseOrNull("file:///d%3A/src/")!!
        var uri = Uris.withPath(initial, "/home/src/test.cql")
        assertEquals("file:///home/src/test.cql", uri.toString())

        uri = Uris.withPath(initial, "home/src/test.cql")
        assertEquals("file:///home/src/test.cql", uri.toString())

        initial = Uris.parseOrNull("https://hl7.org/fhir")!!
        uri = Uris.withPath(initial, "/fhir/test.cql")
        assertEquals("https://hl7.org/fhir/test.cql", uri.toString())

        uri = Uris.withPath(initial, "fhir/test.cql")
        assertEquals("https://hl7.org/fhir/test.cql", uri.toString())

        initial = Uris.parseOrNull("http://locahost:8080/fhir/")!!
        uri = Uris.withPath(initial, "/fhir/test.cql")
        assertEquals("http://locahost:8080/fhir/test.cql", uri.toString())

        uri = Uris.withPath(initial, "fhir/test.cql")
        assertEquals("http://locahost:8080/fhir/test.cql", uri.toString())
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun toClientStringUnix() {
        val initial = Uris.parseOrNull("file:////d%3A/src/")!!
        val client = Uris.toClientUri(initial)
        assertEquals("file:////d%3A/src/", client)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun toClientStringWindows() {
        var initial = Uris.parseOrNull("file://d%3A/src/")!!
        var client = Uris.toClientUri(initial)
        assertEquals("file:/d:/src", client)

        initial = Uris.parseOrNull("file:////d%3A/src/")!!
        client = Uris.toClientUri(initial)
        assertEquals("file:/d:/src", client)

        initial = Uris.parseOrNull("file:/d%3A/src/")!!
        client = Uris.toClientUri(initial)
        assertEquals("file:/d:/src", client)
    }
}
