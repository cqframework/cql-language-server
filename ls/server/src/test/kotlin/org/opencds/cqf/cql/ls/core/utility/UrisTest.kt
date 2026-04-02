package org.opencds.cqf.cql.ls.core.utility

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Paths

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

    // -----------------------------------------------------------------------
    // Paths.get(URI).toString() — the safe way to convert a file URI to a
    // filesystem path string.  Used in CompilerOptionsManager, IgContextManager,
    // and CqlCommand to avoid the bugs below:
    //
    //   uri.toURL().path        → "/C:/foo" on Windows (leading slash, wrong)
    //   uri.schemeSpecificPart  → "//C:/foo" on Windows (authority prefix, wrong)
    //
    // Platform   | input URI                        | expected toString()
    // -----------|----------------------------------|----------------------
    // macOS/Linux| file:///home/user/options.json   | /home/user/options.json
    // Windows    | file:///C:/Users/user/options.json| C:\Users\user\options.json
    // -----------------------------------------------------------------------

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun fileUriToFsPath_unix() {
        val uri = Uris.parseOrNull("file:///home/user/cql/options.json")!!
        val fsPath = Paths.get(uri).toString()
        assertEquals("/home/user/cql/options.json", fsPath)
        // Verify the two broken alternatives produce incorrect results on Unix:
        // toURL().path would also work on Unix (no leading-slash bug there), but
        // schemeSpecificPart includes the authority ("//") prefix.
        assertFalse(uri.schemeSpecificPart == fsPath, "schemeSpecificPart should differ from the plain path")
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun fileUriToFsPath_windows() {
        val uri = Uris.parseOrNull("file:///C:/Users/user/cql/options.json")!!
        val fsPath = Paths.get(uri).toString()
        // Expect a Windows-style path; drive letter present, no leading slash
        assertEquals("C:\\Users\\user\\cql\\options.json", fsPath)
        // Verify the two broken alternatives:
        assertFalse(uri.toURL().path == fsPath, "toURL().path has a leading slash and is not a valid FS path on Windows")
        assertFalse(uri.schemeSpecificPart == fsPath, "schemeSpecificPart is not a valid FS path on Windows")
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

    // -----------------------------------------------------------------------
    // addPath("/ig.ini") — documents IgContextManager fix (line 79).
    // Before: Uris.addPath(parent, "/ig.ini")!! → NPE on null
    // After:  Uris.addPath(parent, "/ig.ini") ?: continue  → graceful skip
    //
    // Platform   | input URI                              | expected result
    // -----------|----------------------------------------|-------------------------------
    // all        | http://localhost:8080/workspace/myig   | .../myig/ig.ini  (non-null)
    // macOS/Linux| file:///home/user/workspace/myig       | .../myig/ig.ini  (non-null)
    // Windows    | file:///C:/Users/user/workspace/myig   | .../myig/ig.ini  (non-null)
    // -----------------------------------------------------------------------

    @Test
    fun addPathIgIni_http() {
        val parent = Uris.parseOrNull("http://localhost:8080/workspace/myig")!!
        val result = Uris.addPath(parent, "/ig.ini")
        assertNotNull(result)
        assertEquals("http://localhost:8080/workspace/myig/ig.ini", result.toString())
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun addPathIgIni_unix() {
        val parent = Uris.parseOrNull("file:///home/user/workspace/myig")!!
        val result = Uris.addPath(parent, "/ig.ini")
        assertNotNull(result)
        assertEquals("file:///home/user/workspace/myig/ig.ini", result.toString())
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun addPathIgIni_windows() {
        val parent = Uris.parseOrNull("file:///C:/Users/user/workspace/myig")!!
        val result = Uris.addPath(parent, "/ig.ini")
        assertNotNull(result)
        // URI remains forward-slash on Windows
        assertEquals("file:///C:/Users/user/workspace/myig/ig.ini", result.toString())
    }

    // -----------------------------------------------------------------------
    // addPath chain — documents CqlCommand fix (lines 157–159).
    // parseOrNull(rd) → addPath("input") → addPath("cql")
    // Before: each step forced with !! → NPE on null
    // After:  ?.let chain → null propagates safely
    //
    // Platform   | input                                    | expected
    // -----------|------------------------------------------|----------------------------
    // macOS/Linux| file:///home/user/projects/myproject     | .../myproject/input/cql
    // Windows    | file:///C:/Users/user/projects/myproject | .../myproject/input/cql
    // all        | C:\Users\user\projects\myproject         | null (parseOrNull returns null)
    // -----------------------------------------------------------------------

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun cqlCommandPathChain_unix() {
        val rootUri = Uris.parseOrNull("file:///home/user/projects/myproject")
        assertNotNull(rootUri)
        val inputUri = rootUri?.let { Uris.addPath(it, "input") }
        assertNotNull(inputUri)
        assertEquals("file:///home/user/projects/myproject/input", inputUri.toString())
        val cqlUri = inputUri?.let { Uris.addPath(it, "cql") }
        assertNotNull(cqlUri)
        assertEquals("file:///home/user/projects/myproject/input/cql", cqlUri.toString())
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun cqlCommandPathChain_windowsForwardSlash() {
        // Windows file URI with forward slashes — chain resolves successfully
        val rootUri = Uris.parseOrNull("file:///C:/Users/user/projects/myproject")
        assertNotNull(rootUri)
        val cqlUri =
            rootUri
                ?.let { Uris.addPath(it, "input") }
                ?.let { Uris.addPath(it, "cql") }
        assertNotNull(cqlUri)
    }

    @Test
    fun cqlCommandPathChain_windowsBackslash() {
        // Raw Windows backslash path: parseOrNull returns null on all platforms.
        // This documents why the ?.let chain in CqlCommand is safe — it stops here
        // rather than throwing NPE.
        val rootUri = Uris.parseOrNull("C:\\Users\\user\\projects\\myproject")
        assertNull(rootUri, "Raw Windows backslash paths return null from parseOrNull")
        val cqlUri =
            rootUri
                ?.let { Uris.addPath(it, "input") }
                ?.let { Uris.addPath(it, "cql") }
        assertNull(cqlUri)
    }
}
