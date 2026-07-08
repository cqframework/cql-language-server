package org.opencds.cqf.cql.ls.server.provider

import kotlinx.io.readString
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Converters
import org.opencds.cqf.cql.ls.server.service.NpmLibraryMaterializationCache
import org.opencds.cqf.cql.ls.server.service.TestContentService
import java.io.File
import java.io.InputStream
import java.net.URI

class FederatedLibrarySourceProviderTest {
    // TestContentService resolves classpath resources under /org/opencds/cqf/cql/ls/server/
    private val root: URI = URI.create("file:///workspace/")
    private val cs: ContentService = TestContentService()

    @TempDir
    lateinit var npmCacheRoot: File

    // -----------------------------------------------------------------------
    // ContentService tier — known library returns a source
    // -----------------------------------------------------------------------

    @Test
    fun getLibrarySource_knownLibrary_returnsSource() {
        val provider = FederatedLibrarySourceProvider(root, cs, null)
        // "One" is a fixture library in the test classpath
        assertNotNull(provider.getLibrarySource(VersionedIdentifier().withId("One")))
    }

    // -----------------------------------------------------------------------
    // ContentService tier — unknown library returns null
    // -----------------------------------------------------------------------

    @Test
    fun getLibrarySource_unknownLibrary_returnsNull() {
        val provider = FederatedLibrarySourceProvider(root, cs, null)
        assertNull(provider.getLibrarySource(VersionedIdentifier().withId("DoesNotExist")))
    }

    // -----------------------------------------------------------------------
    // NPM tier skipped — null package context
    // -----------------------------------------------------------------------

    @Test
    fun getLibrarySource_nullPackageContext_returnsNull() {
        val emptyCs = emptyContentService()
        val provider = FederatedLibrarySourceProvider(root, emptyCs, null)
        assertNull(provider.getLibrarySource(VersionedIdentifier().withId("FHIRHelpers")))
    }

    // -----------------------------------------------------------------------
    // ContentService exception — swallowed, returns null
    // -----------------------------------------------------------------------

    @Test
    fun getLibrarySource_contentServiceLocateThrows_returnsNull() {
        val throwingCs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ): Set<URI> = throw RuntimeException("simulated locate failure")

                override fun read(uri: URI): InputStream? = null
            }
        val provider = FederatedLibrarySourceProvider(root, throwingCs, null)
        assertNull(provider.getLibrarySource(VersionedIdentifier().withId("Any")))
    }

    // -----------------------------------------------------------------------
    // Multiple locations — returns first match without throwing
    // -----------------------------------------------------------------------

    @Test
    fun getLibrarySource_multipleLocations_returnsFirstWithoutThrowing() {
        val uri1 = URI.create("file:///workspace/LibA-1.0.0.cql")
        val uri2 = URI.create("file:///workspace2/LibA-1.0.0.cql")
        val content = "library LibA version '1.0.0'\ndefine X: 1".toByteArray()
        val multiCs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ): Set<URI> = setOf(uri1, uri2)

                override fun read(uri: URI): InputStream = content.inputStream()
            }
        val provider = FederatedLibrarySourceProvider(root, multiCs, null)
        // Default ContentService.read(root, identifier) would throw — our provider must not
        assertNotNull(provider.getLibrarySource(VersionedIdentifier().withId("LibA")))
    }

    // -----------------------------------------------------------------------
    // read(uri) exception — swallowed, returns null
    // -----------------------------------------------------------------------

    @Test
    fun getLibrarySource_contentServiceReadThrows_returnsNull() {
        val uri1 = URI.create("file:///workspace/LibA.cql")
        val throwingReadCs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ): Set<URI> = setOf(uri1)

                override fun read(uri: URI): InputStream? = throw RuntimeException("simulated read failure")
            }
        val provider = FederatedLibrarySourceProvider(root, throwingReadCs, null)
        assertNull(provider.getLibrarySource(VersionedIdentifier().withId("LibA")))
    }

    // -----------------------------------------------------------------------
    // Root URI passthrough — FederatedLibrarySourceProvider does NOT normalize
    // file URIs to directory URIs (unlike CqlCompilationManager which calls
    // Uris.getHead first). When CqlEvaluator passes libraryUri (a .cql file),
    // it arrives at locate() as-is — no directory normalization.
    // -----------------------------------------------------------------------

    @Test
    fun getLibrarySource_passesRootUriThroughWithoutNormalization() {
        var capturedRoot: URI? = null
        val capturingCs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ): Set<URI> {
                    capturedRoot = root
                    return emptySet()
                }

                override fun read(uri: URI): InputStream? = null
            }

        val fileUri = URI.create("file:///workspace/input/cql/MyLibrary.cql")
        val provider = FederatedLibrarySourceProvider(fileUri, capturingCs, null)
        provider.getLibrarySource(VersionedIdentifier().withId("Test"))

        // Root is passed through unchanged — no getHead normalization.
        // This means locate() receives a file URI, forcing tier1 to fail
        // (BFS requires a directory) and relying entirely on tier2 fallback.
        assertEquals(fileUri, capturedRoot, "Root must be passed through without normalization")
    }

    // -----------------------------------------------------------------------
    // materializeAndRewrap — NPM-resolved content is materialized to disk AND
    // the returned Source is unaffected (compile path unchanged)
    // -----------------------------------------------------------------------

    @Test
    fun materializeAndRewrap_returnsUnchangedContent_andMaterializesToCache() {
        val provider = FederatedLibrarySourceProvider(root, cs, null)
        val identifier =
            VersionedIdentifier().withId("FHIRHelpers").withVersion("4.0.1").withSystem("http://hl7.org/fhir/uv/cql")
        val cqlText = "library FHIRHelpers version '4.0.1'\ndefine function ToCode(x String): null"

        val rewrapped = provider.materializeAndRewrap(identifier, Converters.stringToSource(cqlText), npmCacheRoot)

        assertEquals(cqlText, rewrapped.readString(), "Returned Source content must be unchanged")
        val materialized = NpmLibraryMaterializationCache.lookup(identifier, npmCacheRoot)
        assertNotNull(materialized, "Expected the identifier to be materialized to disk as a side effect")
        assertTrue(materialized!!.readText().contains(cqlText))
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun emptyContentService() =
        object : ContentService {
            override fun locate(
                root: URI,
                identifier: VersionedIdentifier,
            ): Set<URI> = emptySet()

            override fun read(uri: URI): InputStream? = null
        }
}
