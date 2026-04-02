package org.opencds.cqf.cql.ls.server.provider

import org.eclipse.lsp4j.Position
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.service.TestContentService
import java.io.InputStream
import java.net.URI

class FormattingProviderTest {
    @Test
    fun format_validCql_returnsOneEdit() {
        val edits = formattingProvider.format("/org/opencds/cqf/cql/ls/server/Two.cql")
        assertEquals(1, edits.size)
        val edit = edits[0]
        assertEquals(Position(0, 0), edit.range.start)
        assertNotNull(edit.newText)
        assertFalse(edit.newText.isBlank())
    }

    @Test
    fun format_syntaxError_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException::class.java) {
            formattingProvider.format("/org/opencds/cqf/cql/ls/server/SyntaxError.cql")
        }
    }

    // -----------------------------------------------------------------------
    // content-not-found — read() returns null → IllegalArgumentException
    // -----------------------------------------------------------------------

    @Test
    fun format_contentNotFound_throwsIllegalArgument() {
        val nullCs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ): Set<URI> = emptySet()

                override fun read(uri: URI): InputStream? = null
            }
        val provider = FormattingProvider(nullCs)
        assertThrows(IllegalArgumentException::class.java) {
            provider.format("file:///some/path/Missing.cql")
        }
    }

    // -----------------------------------------------------------------------
    // invalid URI — parseOrNull returns null → requireNotNull throws
    // -----------------------------------------------------------------------

    @Test
    fun format_invalidUri_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException::class.java) {
            formattingProvider.format("not a valid uri with spaces")
        }
    }

    companion object {
        private lateinit var formattingProvider: FormattingProvider

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val cs: ContentService = TestContentService()
            formattingProvider = FormattingProvider(cs)
        }
    }
}
