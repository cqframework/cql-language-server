package org.opencds.cqf.cql.ls.server.provider

import org.hl7.cql.model.ModelIdentifier
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertNull
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
}
