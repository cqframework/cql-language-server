package org.opencds.cqf.cql.ls.server.provider

import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService
import java.io.InputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class CompletionProviderTest {
    companion object {
        private lateinit var provider: CompletionProvider
        private lateinit var compilationManager: CqlCompilationManager
        private lateinit var cs: TestContentService

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            cs = TestContentService()
            compilationManager =
                CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
            val hoverProvider = HoverProvider(compilationManager, cs)
            provider = CompletionProvider(compilationManager, cs, hoverProvider)
        }
    }

    @Test
    fun completion_atTopLevel_includesKeywords() {
        val items = provider.completion(
            CompletionParams(
                TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/One.cql"),
                Position(0, 0),
            ),
        )
        assertFalse(items.isEmpty())
        val names = items.map { it.label }
        assertTrue(names.contains("define"), "Expected 'define' keyword, got: $names")
        assertTrue(names.contains("using"), "Expected 'using' keyword, got: $names")
        assertTrue(names.contains("library"), "Expected 'library' keyword, got: $names")
        items.forEach { assertEquals(CompletionItemKind.Keyword, it.kind, "Expected keyword kind for: ${it.label}") }
    }

    @Test
    fun completion_inExpressionBody_includesLocalDefs() {
        val items = provider.completion(
            CompletionParams(
                TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/Two.cql"),
                Position(5, 4),
            ),
        )
        assertFalse(items.isEmpty())
        val names = items.map { it.label }
        assertTrue(names.contains("Two"), "Expected define 'Two' in completions, got: $names")
        assertTrue(names.contains("Two List"), "Expected define 'Two List' in completions, got: $names")
        assertTrue(names.contains("TwoBoolOr"), "Expected define 'TwoBoolOr' in completions, got: $names")
        val twoItem = items.first { it.label == "Two" }
        assertEquals(CompletionItemKind.Variable, twoItem.kind, "Expected Variable kind for expression def")
    }

    @Test
    fun completion_localFunction_hasKindFunction() {
        val items = provider.completion(
            CompletionParams(
                TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionLib.cql"),
                Position(9, 4),
            ),
        )
        assertFalse(items.isEmpty())
        val names = items.map { it.label }
        assertTrue(names.contains("Double"), "Expected function 'Double' in completions, got: $names")
        val doubleItem = items.first { it.label == "Double" }
        assertEquals(CompletionItemKind.Function, doubleItem.kind, "Expected Function kind for function def")
    }

    @Test
    fun completion_afterLibraryAliasDot_includesPublicDefs() {
        val items = provider.completion(
            CompletionParams(
                TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql"),
                Position(5, 4),
            ),
        )
        assertFalse(items.isEmpty())
        val names = items.map { it.label }
        assertTrue(names.contains("MyValue"), "Expected 'MyValue' from FunctionLib, got: $names")
        assertTrue(names.contains("Double"), "Expected 'Double' from FunctionLib, got: $names")
    }

    @Test
    fun completion_libraryQualifiedItem_hasQuotedInsertText() {
        val items = provider.completion(
            CompletionParams(
                TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql"),
                Position(5, 4),
            ),
        )
        assertFalse(items.isEmpty())
        val myValueItem = items.first { it.label == "MyValue" }
        assertEquals("\"MyValue\"", myValueItem.insertText, "Expected quoted insertText for library-qualified member")
    }

    @Test
    fun completion_libraryQualified_includesParameters() {
        val items = provider.completion(
            CompletionParams(
                TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithParamCaller.cql"),
                Position(5, 5),
            ),
        )
        assertFalse(items.isEmpty())
        val names = items.map { it.label }
        assertTrue(names.contains("Measurement Period"), "Expected parameter from WithParam, got: $names")
        assertTrue(names.contains("Rate"), "Expected parameter 'Rate' from WithParam, got: $names")
    }

    @Test
    fun completion_privateLibraryDefs_excluded() {
        val items = provider.completion(
            CompletionParams(
                TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/PrivateLibCaller.cql"),
                Position(5, 5),
            ),
        )
        assertFalse(items.isEmpty())
        val names = items.map { it.label }
        assertFalse(names.contains("PrivateDef"), "Private def should be excluded, got: $names")
        assertTrue(names.contains("PublicDef"), "Public def should be included, got: $names")
    }

    @Test
    fun completion_afterFhirDot_includesProperties() {
        val items = provider.completion(
            CompletionParams(
                TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/DotAccess.cql"),
                Position(10, 11),
            ),
        )
        assertFalse(items.isEmpty(), "Expected FHIR Encounter properties, got empty")
        val names = items.map { it.label }
        assertTrue(names.contains("period"), "Expected property 'period' of FHIR.Encounter, got: $names")
        assertTrue(names.contains("status"), "Expected property 'status' of FHIR.Encounter, got: $names")
        assertEquals(CompletionItemKind.Property, items.first().kind, "Expected Property kind for dot-access items")
    }

    @Test
    fun completion_dotAccess_hasDotTextEdit() {
        val items = provider.completion(
            CompletionParams(
                TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/DotAccess.cql"),
                Position(10, 11),
            ),
        )
        assertFalse(items.isEmpty())
        items.forEach { item ->
            assertNotNull(item.textEdit, "Expected textEdit for dot-access item: ${item.label}")
            val textEdit = item.textEdit.left
            assertEquals(10, textEdit.range.start.line, "textEdit start line should be cursor line")
            assertEquals(11, textEdit.range.start.character, "textEdit should start after the dot at column 11")
            assertEquals(10, textEdit.range.end.line, "textEdit end line should be cursor line")
            assertEquals(11, textEdit.range.end.character, "textEdit end should be at cursor position")
        }
    }

    @Test
    fun completion_terminologyDefs_included() {
        val items = provider.completion(
            CompletionParams(
                TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithTerminology.cql"),
                Position(6, 8),
            ),
        )
        assertFalse(items.isEmpty())
        val names = items.map { it.label }
        assertTrue(names.contains("Beta Blocker Therapy"), "Expected valueset in completions, got: $names")
        assertTrue(names.contains("SNOMEDCT"), "Expected codesystem in completions, got: $names")
        val vsItem = items.first { it.label == "Beta Blocker Therapy" }
        assertEquals(CompletionItemKind.Enum, vsItem.kind, "Expected Enum kind for valueset")
        val csItem = items.first { it.label == "SNOMEDCT" }
        assertEquals(CompletionItemKind.Module, csItem.kind, "Expected Module kind for codesystem")
    }

    @Test
    fun completion_syntaxError_doesNotThrow() {
        val items = assertDoesNotThrow {
            provider.completion(
                CompletionParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/SyntaxError.cql"),
                    Position(1, 0),
                ),
            )
        }
        assertNotNull(items, "Should return non-null list on syntax error")
        assertTrue(items.isNotEmpty(), "Should return at least keyword completions on syntax error")
    }

    @Test
    fun completion_missingInclude_doesNotThrow() {
        val items = assertDoesNotThrow {
            provider.completion(
                CompletionParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/MissingInclude.cql"),
                    Position(1, 0),
                ),
            )
        }
        assertNotNull(items, "Should return non-null list on missing include")
    }

    // ── Bug-proving tests ──────────────────────────────────────────────────────
    // These tests are expected to FAIL until the underlying bugs are fixed.
    // Each one demonstrates a specific defect identified in code review.

    /**
     * Bug #1: resolveAliasTypeFromAntlrWithContext uses hover position semantics
     * (cursor ON a token) but completion positions place the cursor AFTER the last
     * typed character. CqlParseTreeVisitor.containsPosition returns false when
     * position.character >= stopChar, so a cursor right at the end of "period"
     * (column 17) is excluded from every token on that line and alias resolution
     * returns null — leaving the completion list empty.
     *
     * DotAccess.cql line 10: "  return E.period" (length 17)
     * Position(10, 17) = cursor at end of "period" = normal Ctrl+Space trigger position.
     */
    @Test
    fun completion_dotAccess_cursorAtEndOfTypedWord_returnsProperties() {
        // Position(10, 17): cursor immediately after the final 'period' character.
        // This is the natural position when a user finishes typing and presses Ctrl+Space.
        val items = provider.completion(
            CompletionParams(
                TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/DotAccess.cql"),
                Position(10, 17),
            ),
        )
        // Bug: resolveAliasTypeFromAntlrWithContext fails at this position because
        // containsPosition(periodToken, Position(10,17)) returns false (17 >= stopChar 17).
        // Fix: adjust position one character back before calling findDeepestContext, or
        // use the prefix text from the line scan to locate the alias independently of position.
        assertTrue(items.isNotEmpty(), "Expected FHIR Encounter properties when cursor is at end of typed word; got empty list (position semantics bug)")
        val names = items.map { it.label }
        assertTrue(names.contains("period"), "Expected 'period' in properties, got: $names")
        items.forEach { assertEquals(CompletionItemKind.Property, it.kind) }
    }

    /**
     * Verifies that private functions declared with correct CQL grammar
     * ("define private function") are excluded from library-qualified completion.
     *
     * With correct grammar, the CQL compiler sets accessLevel = PRIVATE on the FunctionDef
     * and the first guard in isPrivateDef (`if (accessLevel == AccessModifier.PRIVATE) return true`)
     * catches it — so the source-text fallback is never reached.
     *
     * The remaining risk flagged in code review: isPrivateDef's source-text fallback searches
     * for "private define \"$name\"" which would NOT match "define private function \"$name\""
     * if a private function ever reached that path (e.g., a compiler version that mislabels
     * all defs as PUBLIC). That scenario is not tested here because it requires a mocked
     * FunctionDef with PUBLIC accessLevel that still appears in statements.def.
     *
     * PrivateLib.cql now contains:
     *   define private function "PrivateFn"(x Integer): x * 2  ← must stay excluded
     *   define function "PublicFn"(x Integer): x               ← must stay included
     */
    @Test
    fun completion_privateFunction_excluded() {
        val items = provider.completion(
            CompletionParams(
                TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/PrivateLibCaller.cql"),
                Position(5, 5),
            ),
        )
        assertFalse(items.isEmpty(), "Expected public defs from PrivateLib, got empty list")
        val names = items.map { it.label }
        // PublicFn appearing confirms library-qualified completion fired correctly.
        assertTrue(names.contains("PublicFn"), "Expected public function 'PublicFn', got: $names")
        assertFalse(names.contains("PrivateFn"), "Private function 'PrivateFn' should be excluded, got: $names")
    }

    /**
     * Bug #4: isPrivateDef reads the entire source file once per definition in the
     * included library. For a library with N public defs, contentService.read() is
     * called N times per completion request. This test counts read() invocations on
     * the included library URI and asserts it is called at most once.
     */
    @Test
    fun completion_libraryQualified_readsIncludedSourceAtMostOnce() {
        val readCount = AtomicInteger(0)
        val countingCs = object : ContentService by cs {
            override fun read(uri: URI): InputStream? {
                if (uri.toString().endsWith("PrivateLib.cql")) readCount.incrementAndGet()
                return cs.read(uri)
            }
        }
        val localHoverProvider = HoverProvider(compilationManager, countingCs)
        val localProvider = CompletionProvider(compilationManager, countingCs, localHoverProvider)

        // Prime the compilation cache so the read count reflects only completion-time reads.
        localProvider.completion(
            CompletionParams(
                TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/PrivateLibCaller.cql"),
                Position(5, 5),
            ),
        )
        readCount.set(0)

        // This is the measured request.
        val items = localProvider.completion(
            CompletionParams(
                TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/PrivateLibCaller.cql"),
                Position(5, 5),
            ),
        )
        assertFalse(items.isEmpty())
        // Bug: isPrivateDef calls contentService.read(incUri) once per def in the library.
        // PrivateLib now has 4 defs (PrivateDef, PublicDef, PrivateFn, PublicFn),
        // so read() is called 4 times. Fix: read once and cache outside the loop.
        assertTrue(
            readCount.get() <= 1,
            "Expected PrivateLib.cql to be read at most once per completion request, but read ${readCount.get()} times",
        )
    }
}
