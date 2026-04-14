package org.opencds.cqf.cql.ls.core

import org.apache.commons.lang3.NotImplementedException
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.InputStream
import java.net.URI
import java.nio.file.Files

class ContentServiceTest {
    private fun id(
        name: String,
        version: String? = null,
    ): VersionedIdentifier = VersionedIdentifier().withId(name).let { if (version != null) it.withVersion(version) else it }

    private val root = URI("file:///workspace/root/")

    // -------------------------------------------------------------------------
    // locate() — default implementation
    // -------------------------------------------------------------------------

    @Test
    fun `locate default throws NotImplementedException`() {
        val cs = object : ContentService {}
        assertThrows<NotImplementedException> { cs.locate(root, id("Foo")) }
    }

    // -------------------------------------------------------------------------
    // read(root, identifier) — template-method behaviour
    // -------------------------------------------------------------------------

    @Test
    fun `read returns null when locate finds no locations`() {
        val cs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ) = emptySet<URI>()
            }
        assertNull(cs.read(root, id("Foo")))
    }

    @Test
    fun `read delegates to read(uri) when locate finds a single location`() {
        val targetUri = URI("file:///workspace/root/Foo.cql")
        val sentinel: InputStream = "sentinel".byteInputStream()
        val cs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ) = setOf(targetUri)

                override fun read(uri: URI) = if (uri == targetUri) sentinel else null
            }
        assertSame(sentinel, cs.read(root, id("Foo")))
    }

    @Test
    fun `read throws IllegalStateException when locate finds multiple locations`() {
        val cs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ) = setOf(URI("file:///a/Foo.cql"), URI("file:///b/Foo.cql"))
            }
        assertThrows<IllegalStateException> { cs.read(root, id("Foo")) }
    }

    @Test
    fun `IllegalStateException message includes library id and version`() {
        val cs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ) = setOf(URI("file:///a/MyLib.cql"), URI("file:///b/MyLib.cql"))
            }
        val ex = assertThrows<IllegalStateException> { cs.read(root, id("MyLib", "2.3.0")) }
        assertTrue(ex.message!!.contains("MyLib"), "message should include library id")
        assertTrue(ex.message!!.contains("2.3.0"), "message should include library version")
    }

    // -------------------------------------------------------------------------
    // read(uri) — default implementation
    // -------------------------------------------------------------------------

    @Test
    fun `read(uri) opens stream for a valid file URI`() {
        val tempFile = Files.createTempFile("content-service-test", ".cql")
        try {
            Files.write(tempFile, "library Test".toByteArray())
            val cs = object : ContentService {}
            val stream = cs.read(tempFile.toUri())
            assertNotNull(stream)
            stream!!.close()
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `read(uri) returns null for a non-existent file URI`() {
        val cs = object : ContentService {}
        val uri = URI("file:///nonexistent/path/that/does/not/exist/Foo.cql")
        assertNull(cs.read(uri))
    }
}
