package org.opencds.cqf.cql.ls.server.provider

import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.service.TestContentService

class HoverProviderTest {
    companion object {
        private lateinit var hoverProvider: HoverProvider

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val cs = TestContentService()
            val cqlCompilationManager =
                CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs))
            hoverProvider = HoverProvider(cqlCompilationManager)
        }
    }

    @Test
    fun hoverInt() {
        val hover =
            hoverProvider.hover(
                HoverParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/Two.cql"),
                    Position(5, 2),
                ),
            )

        assertNotNull(hover)
        assertNotNull(hover!!.contents.right)

        val markup = hover.contents.right
        assertEquals("markdown", markup.kind)
        assertEquals("```cql\nSystem.Integer\n```", markup.value)
    }

    @Test
    fun hoverNothing() {
        val hover =
            hoverProvider.hover(
                HoverParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/Two.cql"),
                    Position(2, 0),
                ),
            )

        assertNull(hover)
    }

    @Test
    fun hoverList() {
        val hover =
            hoverProvider.hover(
                HoverParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/Two.cql"),
                    Position(8, 2),
                ),
            )

        assertNotNull(hover)
        assertNotNull(hover!!.contents.right)

        val markup = hover.contents.right
        assertEquals("markdown", markup.kind)
        assertEquals("```cql\nlist<System.Integer>\n```", markup.value)
    }

    @Test
    fun hoverOnLibraryRef() {
        // Line 5 (0-indexed): "    1 + One."One"" — position (5, 8) is 'O' in 'One'
        val hover =
            hoverProvider.hover(
                HoverParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/Two.cql"),
                    Position(5, 8),
                ),
            )

        assertNotNull(hover)
        assertNotNull(hover!!.contents.right)

        val markup = hover.contents.right
        assertEquals("markdown", markup.kind)
        assertEquals("```cql\nSystem.Integer\n```", markup.value)
    }

    @Test
    fun hoverOnDefineName() {
        // Line 4 (0-indexed): "define "Two":" — position (4, 8) is 'T' inside the define name
        val hover =
            hoverProvider.hover(
                HoverParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/Two.cql"),
                    Position(4, 8),
                ),
            )

        // ExpressionDef TrackBack covers the define header — expect Integer type
        assertNotNull(hover)
        assertNotNull(hover!!.contents.right)

        val markup = hover.contents.right
        assertEquals("markdown", markup.kind)
        assertEquals("```cql\nSystem.Integer\n```", markup.value)
    }
}
