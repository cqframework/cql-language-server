package org.opencds.cqf.cql.ls.server.command

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertThrows
import org.opencds.cqf.cql.ls.core.utility.Uris
import java.io.ByteArrayOutputStream
import java.io.PrintStream

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CqlCommandTest {
    private lateinit var originalOut: PrintStream
    private lateinit var capturedOut: ByteArrayOutputStream

    @BeforeEach
    fun captureStdout() {
        originalOut = System.out
        capturedOut = ByteArrayOutputStream()
        System.setOut(PrintStream(capturedOut))
    }

    @AfterEach
    fun restoreStdout() {
        System.setOut(originalOut)
    }

    private fun output(): String = capturedOut.toString()

    /**
     * Returns a file: URI pointing to the directory containing the CQL test fixtures.
     *
     * Uses .toURI() (not URI.create()) so the path is correct on all platforms:
     *   macOS/Linux: file:///home/user/...
     *   Windows:     file:///C:/Users/...
     */
    private fun cqlFixtureDirectoryUrl(): String =
        CqlCommandTest::class.java
            .getResource("/org/opencds/cqf/cql/ls/server/One.cql")!!
            .toURI()
            .resolve(".")
            .toString()

    private fun buildCommand(
        libraryName: String,
        libraryVersion: String? = null,
        expressions: Array<String>? = null,
        fhirVersion: String = "R4",
    ): CqlCommand {
        val cmd = CqlCommand()
        cmd.fhirVersion = fhirVersion
        val lib = CqlCommand.LibraryParameter()
        lib.libraryUrl = cqlFixtureDirectoryUrl()
        lib.libraryName = libraryName
        lib.libraryVersion = libraryVersion
        lib.expression = expressions
        cmd.libraries = mutableListOf(lib)
        return cmd
    }

    // -------------------------------------------------------------------------
    // Tests 1–12: execution paths, output formatting, and error cases
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `happy path - One evaluates One=1`() {
        val cmd = buildCommand("One")
        val result = cmd.call()
        assertEquals(0, result)
        assertTrue(output().contains("One=1"))
    }

    @Test
    @Order(2)
    fun `happy path - Two with version 1-0-0 evaluates Two=2`() {
        val cmd = buildCommand("Two", libraryVersion = "1.0.0")
        val result = cmd.call()
        assertEquals(0, result)
        assertTrue(output().contains("Two=2"))
    }

    @Test
    @Order(3)
    fun `expression filter - only requested expression appears`() {
        val cmd = buildCommand("Two", expressions = arrayOf("Two"))
        cmd.call()
        val out = output()
        assertTrue(out.contains("Two=2"), "Expected 'Two=2' in output")
        assertFalse(out.contains("Two List"), "Expected 'Two List' to be filtered out when -e Two is set")
    }

    @Test
    @Order(4)
    fun `no expression filter - all defines appear`() {
        val cmd = buildCommand("Two")
        cmd.call()
        val out = output()
        assertTrue(out.contains("Two=2"))
        assertTrue(out.contains("Two List"))
    }

    @Test
    @Order(5)
    fun `Two List is formatted as bracket list`() {
        val cmd = buildCommand("Two")
        cmd.call()
        assertTrue(output().contains("Two List=[1, 2, 3]"))
    }

    @Test
    @Order(6)
    fun `blank line appears after expression results`() {
        val cmd = buildCommand("One")
        cmd.call()
        // writeResult() calls println() after iterating results, producing a trailing blank line
        val out = output()
        assertTrue(out.contains("\n\n") || out.endsWith("\n\n"))
    }

    @Test
    @Order(7)
    fun `null value is rendered as string null`() {
        val cmd = buildCommand("NullResult")
        cmd.call()
        assertTrue(output().contains("NullDef=null"))
    }

    @Test
    @Order(8)
    fun `call returns 0 on success`() {
        val cmd = buildCommand("One")
        assertEquals(0, cmd.call())
    }

    @Test
    @Order(9)
    fun `NoOpRepository path - evaluation succeeds without terminology or model`() {
        // buildCommand sets no terminologyUrl or modelUrl, so createRepository returns NoOpRepository
        val cmd = buildCommand("One")
        val result = cmd.call()
        assertEquals(0, result)
        assertTrue(output().contains("One=1"))
    }

    @Test
    @Order(10)
    fun `R5 fhir version - evaluation succeeds`() {
        val cmd = buildCommand("One", fhirVersion = "R5")
        val result = cmd.call()
        assertEquals(0, result)
        assertTrue(output().contains("One=1"))
    }

    @Test
    @Order(11)
    fun `DSTU3 fhir version - evaluation succeeds`() {
        val cmd = buildCommand("One", fhirVersion = "DSTU3")
        val result = cmd.call()
        assertEquals(0, result)
        assertTrue(output().contains("One=1"))
    }

    @Test
    @Order(12)
    fun `invalid fhir version throws IllegalArgumentException`() {
        val cmd = buildCommand("One", fhirVersion = "INVALID")
        assertThrows<IllegalArgumentException> { cmd.call() }
    }

    // -------------------------------------------------------------------------
    // Tests 13–15: cross-platform path handling via Uris.parseOrNull()
    // No CQL engine involvement — pure unit tests of URI parsing behaviour.
    //
    // Platform behaviour matrix:
    //   Format                        | Example                       | parseOrNull result
    //   ------------------------------|-------------------------------|-------------------
    //   Unix / macOS                  | file:///tmp/cql/              | non-null URI
    //   Windows forward-slash         | file:///C:/Users/cql/         | non-null URI (any platform)
    //   Windows backslash             | file:///C:\Users\cql\         | null (URISyntaxException)
    //
    // The backslash case documents a latent NPE in CqlCommand: libraryKotlinPath!! will
    // throw NullPointerException if a backslash path reaches libraryUrl.
    // -------------------------------------------------------------------------

    @Test
    @Order(13)
    fun `unix style file URI parses to valid path`() {
        val uri = Uris.parseOrNull("file:///tmp/cql/")
        assertNotNull(uri)
        assertEquals("file", uri!!.scheme)
    }

    @Test
    @Order(14)
    fun `windows forward slash URI parses to valid URI`() {
        val uri = Uris.parseOrNull("file:///C:/Users/cql/")
        assertNotNull(uri)
        assertEquals("file", uri!!.scheme)
    }

    @Test
    @Order(15)
    fun `windows backslash URI returns null from parseOrNull`() {
        // Backslashes are illegal in URIs per RFC 3986; URI(String) throws URISyntaxException,
        // which parseOrNull catches and converts to null. This documents the latent NPE risk:
        // CqlCommand line `libraryKotlinPath!!` will throw if this reaches libraryUrl.
        val uri = Uris.parseOrNull("file:///C:\\Users\\cql\\")
        assertNull(uri, "Backslash paths are not valid URIs; parseOrNull should return null")
    }
}
