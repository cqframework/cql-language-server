package org.opencds.cqf.cql.ls.server.provider

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.service.TestContentService

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
