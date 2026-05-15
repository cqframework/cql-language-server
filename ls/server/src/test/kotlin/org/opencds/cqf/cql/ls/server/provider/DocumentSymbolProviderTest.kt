package org.opencds.cqf.cql.ls.server.provider

import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService

class DocumentSymbolProviderTest {
    companion object {
        private lateinit var provider: DocumentSymbolProvider

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val cs = TestContentService()
            val compilationManager =
                CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
            provider = DocumentSymbolProvider(compilationManager)
        }
    }

    // -------------------------------------------------------------------------
    // FunctionLib.cql: "MyValue" (Variable) + "Double" (Function) + "UseDouble" (Variable)
    // -------------------------------------------------------------------------

    @Test
    fun documentSymbol_functionLib_returnsCorrectSymbolCount() {
        val params = DocumentSymbolParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionLib.cql"))
        val symbols = provider.documentSymbol(params)
        assertEquals(3, symbols.size, "FunctionLib should have exactly 3 symbols (MyValue + Double + UseDouble)")
    }

    @Test
    fun documentSymbol_functionLib_containsMyValueAsVariable() {
        val params = DocumentSymbolParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionLib.cql"))
        val symbols = provider.documentSymbol(params)
        val myValue = symbols.firstOrNull { it.name == "MyValue" }
        assertTrue(myValue != null, "Expected symbol named 'MyValue'")
        assertEquals(SymbolKind.Variable, myValue!!.kind)
    }

    @Test
    fun documentSymbol_functionLib_containsDoubleAsFunction() {
        val params = DocumentSymbolParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionLib.cql"))
        val symbols = provider.documentSymbol(params)
        val double = symbols.firstOrNull { it.name == "Double" }
        assertTrue(double != null, "Expected symbol named 'Double'")
        assertEquals(SymbolKind.Function, double!!.kind)
    }

    @Test
    fun documentSymbol_functionLib_symbolRangesAreNonZero() {
        val params = DocumentSymbolParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionLib.cql"))
        val symbols = provider.documentSymbol(params)
        for (sym in symbols) {
            assertTrue(
                sym.range.start.line > 0 || sym.range.start.character > 0,
                "Symbol '${sym.name}' should have a non-zero range start (is past the library header)",
            )
        }
    }

    // -------------------------------------------------------------------------
    // FunctionCaller.cql: 2 expression defs ("UseValue" + "UseFunction")
    // -------------------------------------------------------------------------

    @Test
    fun documentSymbol_functionCaller_returnsTwoVariableSymbols() {
        val params = DocumentSymbolParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql"))
        val symbols = provider.documentSymbol(params)
        assertEquals(2, symbols.size, "FunctionCaller should have exactly 2 symbols")
        assertTrue(symbols.all { it.kind == SymbolKind.Variable }, "All FunctionCaller symbols should be Variable kind")
    }

    // -------------------------------------------------------------------------
    // Unresolvable URI — should return empty list gracefully
    // -------------------------------------------------------------------------

    @Test
    fun documentSymbol_unresolvableUri_returnsEmpty() {
        val params = DocumentSymbolParams(TextDocumentIdentifier("/does/not/exist.cql"))
        val symbols = provider.documentSymbol(params)
        assertTrue(symbols.isEmpty(), "Expected empty list for unresolvable URI")
    }
}
