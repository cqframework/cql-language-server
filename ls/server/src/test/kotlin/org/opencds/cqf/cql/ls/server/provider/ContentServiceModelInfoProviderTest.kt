package org.opencds.cqf.cql.ls.server.provider

import org.hl7.cql.model.ModelIdentifier
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import java.io.InputStream
import java.net.URI

class ContentServiceModelInfoProviderTest {
    private val root = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server")!!

    private fun nullContentService(): ContentService =
        object : ContentService {
            override fun locate(
                root: URI,
                identifier: VersionedIdentifier,
            ): Set<URI> = emptySet()

            override fun read(uri: URI): InputStream? = null
        }

    // -----------------------------------------------------------------------
    // Content service returns null — load() returns null
    // -----------------------------------------------------------------------

    @Test
    fun load_returnsNull_whenContentServiceReturnsNull() {
        val provider = ContentServiceModelInfoProvider(root, nullContentService())
        assertNull(provider.load(ModelIdentifier(id = "NonExistentModel")))
    }

    // -----------------------------------------------------------------------
    // Version suffix — model identifier with version exercises the "-version" path
    // -----------------------------------------------------------------------

    @Test
    fun load_returnsNull_whenContentServiceReturnsNullForVersionedModel() {
        val provider = ContentServiceModelInfoProvider(root, nullContentService())
        // Exercises the modelVersion?.let { "-$it" } ?: "" branch
        assertNull(provider.load(ModelIdentifier(id = "FHIR", version = "4.0.1")))
    }

    // -----------------------------------------------------------------------
    // Invalid XML — load() wraps the parse exception in IllegalArgumentException
    // -----------------------------------------------------------------------

    @Test
    fun load_throwsIllegalArgumentException_whenContentServiceReturnsMalformedXml() {
        val provider =
            ContentServiceModelInfoProvider(
                root,
                object : ContentService {
                    override fun locate(
                        root: URI,
                        identifier: VersionedIdentifier,
                    ): Set<URI> = emptySet()

                    override fun read(uri: URI): InputStream? = "not valid xml {{{{".byteInputStream()
                },
            )
        assertThrows<IllegalArgumentException> { provider.load(ModelIdentifier(id = "Bad")) }
    }

    // -----------------------------------------------------------------------
    // File URI root — constructs wrong path (treats .cql file as a directory)
    // -----------------------------------------------------------------------
    // CqlEvaluator passes the .cql file URI as root (libraryUri). The provider
    // appends modelinfo paths to it, producing URIs like:
    //   file:///.../MyLibrary.cql/c4bb-modelinfo-2.1.1.xml
    // which fails with "Not a directory" at read time.

    @Test
    fun load_fileUriRoot_appendsModelinfoToCqlFile() {
        var capturedUri: URI? = null
        val capturingService =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ): Set<URI> = emptySet()

                override fun read(uri: URI): InputStream? {
                    capturedUri = uri
                    return null
                }
            }
        val libraryFile = URI.create("file:///workspace/input/cql/MyLibrary.cql")

        val provider = ContentServiceModelInfoProvider(libraryFile, capturingService)
        provider.load(ModelIdentifier(id = "C4BB", version = "2.1.1"))

        assertNotNull(capturedUri)
        assertTrue(
            capturedUri!!.toString().contains("MyLibrary.cql/c4bb-modelinfo-2.1.1.xml"),
            "File URI root causes the .cql file to be treated as a directory: ${capturedUri}",
        )
    }

    // -----------------------------------------------------------------------
    // Directory URI root — constructs correct path
    // -----------------------------------------------------------------------
    // CqlCompilationManager uses Uris.getHead(uri) which strips the filename,
    // giving a directory root. The modelinfo path resolves correctly:
    //   file:///.../input/cql/c4bb-modelinfo-2.1.1.xml

    @Test
    fun load_directoryUriRoot_appendsModelinfoFlat() {
        var capturedUri: URI? = null
        val capturingService =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ): Set<URI> = emptySet()

                override fun read(uri: URI): InputStream? {
                    capturedUri = uri
                    return null
                }
            }
        val cqlDir = URI.create("file:///workspace/input/cql/")

        val provider = ContentServiceModelInfoProvider(cqlDir, capturingService)
        provider.load(ModelIdentifier(id = "C4BB", version = "2.1.1"))

        assertNotNull(capturedUri)
        assertTrue(
            capturedUri!!.toString().endsWith("c4bb-modelinfo-2.1.1.xml"),
            "Directory URI root produces correct flat path: ${capturedUri}",
        )
    }

    // -----------------------------------------------------------------------
    // End-to-end: file URI root cannot resolve valid modelinfo
    // -----------------------------------------------------------------------
    // Even when a content service can serve modelinfo from the correct path,
    // a file URI root constructs the wrong path and never finds it.

    @Test
    fun load_fileUriRoot_cannotResolveModelinfo_whenDirectoryRootCan() {
        val cqlDir = URI.create("file:///workspace/input/cql/")
        val libraryFile = URI.create("file:///workspace/input/cql/MyLibrary.cql")
        val validModelinfo = """<?xml version="1.0" encoding="UTF-8"?><modelInfo xmlns="urn:hl7-org:elm-modelinfo-r1" name="C4BB" version="2.1.1"/>"""

        val correctModelinfoUri = URI.create("file:///workspace/input/cql/c4bb-modelinfo-2.1.1.xml")

        val servingService =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ): Set<URI> = emptySet()

                override fun read(uri: URI): InputStream? {
                    // Only serve from the exact correct path — file-root constructs
                    // "MyLibrary.cql/c4bb-modelinfo-2.1.1.xml" which won't match
                    return if (uri == correctModelinfoUri)
                        validModelinfo.byteInputStream()
                    else null
                }
            }

        // Directory root succeeds — correct flat path
        val dirProvider = ContentServiceModelInfoProvider(cqlDir, servingService)
        assertNotNull(dirProvider.load(ModelIdentifier(id = "C4BB", version = "2.1.1")))

        // File root fails — wrong path (file treated as directory)
        val fileProvider = ContentServiceModelInfoProvider(libraryFile, servingService)
        assertNull(fileProvider.load(ModelIdentifier(id = "C4BB", version = "2.1.1")))
    }
}
