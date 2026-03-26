package org.opencds.cqf.cql.ls.server.provider

import org.hl7.cql.model.ModelIdentifier
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import java.io.InputStream
import java.net.URI

class ContentServiceModelInfoProviderTest {
    private val root = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server")!!

    @Test
    fun load_returnsNull_whenContentServiceReturnsNull() {
        val provider =
            ContentServiceModelInfoProvider(
                root,
                object : ContentService {
                    override fun locate(
                        root: URI,
                        identifier: VersionedIdentifier,
                    ): Set<URI> = emptySet()

                    override fun read(uri: URI): InputStream? = null
                },
            )
        assertNull(provider.load(ModelIdentifier(id = "NonExistentModel")))
    }
}
