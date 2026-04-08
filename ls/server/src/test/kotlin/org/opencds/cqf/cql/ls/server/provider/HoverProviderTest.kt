package org.opencds.cqf.cql.ls.server.provider

import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.hl7.elm.r1.ExpressionDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.service.TestContentService

class HoverProviderTest {
    companion object {
        private lateinit var hoverProvider: HoverProvider
        private lateinit var compilationManager: CqlCompilationManager

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val cs = TestContentService()
            compilationManager = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs))
            hoverProvider = HoverProvider(compilationManager)
        }
    }

    @Disabled("Disabled until LibraryManager caching issues are resolved")
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

    @Disabled("Disabled until LibraryManager caching issues are resolved")
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

    @Disabled("Disabled until LibraryManager caching issues are resolved")
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

    @Disabled("Disabled until LibraryManager caching issues are resolved")
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

    @Disabled("Disabled until LibraryManager caching issues are resolved")
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

    // -----------------------------------------------------------------------
    // hover() — disabled path always returns null
    // -----------------------------------------------------------------------

    @Test
    fun hover_alwaysReturnsNull_whileDisabled() {
        val hover =
            hoverProvider.hover(
                HoverParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/One.cql"),
                    Position(2, 0),
                ),
            )
        assertNull(hover)
    }

    // -----------------------------------------------------------------------
    // markup() — unit tests for the public markup helper
    // -----------------------------------------------------------------------

    @Test
    fun markup_nullDef_returnsNull() {
        assertNull(hoverProvider.markup(null))
    }

    @Test
    fun markup_defWithNoExpression_returnsNull() {
        val def = ExpressionDef()
        // expression is null by default; resultType is irrelevant
        assertNull(hoverProvider.markup(def))
    }

    @Test
    fun markup_compiledDef_returnsMarkdownContent() {
        // Compile One.cql to get a real ExpressionDef with expression and resultType
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        val compiler = compilationManager.compile(uri)!!
        val def = compiler.compiledLibrary!!.library!!.statements!!.def.first()

        val result = hoverProvider.markup(def)

        assertNotNull(result)
        assertEquals("markdown", result!!.kind)
        assertTrue(result.value.startsWith("```cql"), "Expected markdown fenced block to start with ```cql")
        assertTrue(result.value.endsWith("```"), "Expected markdown fenced block to end with ```")
    }
}
