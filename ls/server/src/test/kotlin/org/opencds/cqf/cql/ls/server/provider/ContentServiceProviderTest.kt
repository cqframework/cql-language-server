package org.opencds.cqf.cql.ls.server.provider

import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import java.io.IOException
import java.io.UncheckedIOException
import java.net.URI

class ContentServiceProviderTest {
    @Test
    fun should_throwException_when_gettingLibrary() {
        val versionedIdentifier = VersionedIdentifier()
        versionedIdentifier.withVersion("1.0.0")

        val contentServiceSourceProvider =
            ContentServiceSourceProvider(
                Uris.parseOrNull("/provider/content/sample-library-1.0.0.json")!!,
                object : ContentService {
                    override fun locate(
                        root: URI,
                        identifier: VersionedIdentifier,
                    ): Set<URI> {
                        throw UncheckedIOException(IOException())
                    }
                },
            )

        assertThrows(RuntimeException::class.java) { contentServiceSourceProvider.getLibrarySource(versionedIdentifier) }
    }
}
