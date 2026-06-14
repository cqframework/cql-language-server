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

    // -------------------------------------------------------------------------
    // DocumentSymbolFixture.cql: parameter + codesystem + valueset + code +
    //                             concept + 1 define
    // -------------------------------------------------------------------------

    @Test
    fun documentSymbol_fixture_returnsAllSymbols() {
        val params = DocumentSymbolParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/DocumentSymbolFixture.cql"))
        val symbols = provider.documentSymbol(params)
        assertTrue(symbols.size >= 6, "DocumentSymbolFixture should have at least 6 symbols, got ${symbols.size}")
        assertTrue(symbols.any { it.name == "TestParam" }, "Expected TestParam parameter")
        assertTrue(symbols.any { it.name == "TestCS" }, "Expected TestCS codesystem")
        assertTrue(symbols.any { it.name == "TestVS" }, "Expected TestVS valueset")
        assertTrue(symbols.any { it.name == "TestCode" }, "Expected TestCode code")
        assertTrue(symbols.any { it.name == "TestConcept" }, "Expected TestConcept concept")
        assertTrue(symbols.any { it.name == "TestDefine" }, "Expected TestDefine")
    }

    @Test
    fun documentSymbol_fixture_parameterIsConstant() {
        val params = DocumentSymbolParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/DocumentSymbolFixture.cql"))
        val symbols = provider.documentSymbol(params)
        val param = symbols.firstOrNull { it.name == "TestParam" }
        assertTrue(param != null, "Expected symbol named 'TestParam'")
        assertEquals(SymbolKind.Constant, param!!.kind)
    }

    @Test
    fun documentSymbol_fixture_codesystemIsModule() {
        val params = DocumentSymbolParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/DocumentSymbolFixture.cql"))
        val symbols = provider.documentSymbol(params)
        val cs = symbols.firstOrNull { it.name == "TestCS" }
        assertTrue(cs != null, "Expected symbol named 'TestCS'")
        assertEquals(SymbolKind.Module, cs!!.kind)
    }

    @Test
    fun documentSymbol_fixture_valuesetIsEnum() {
        val params = DocumentSymbolParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/DocumentSymbolFixture.cql"))
        val symbols = provider.documentSymbol(params)
        val vs = symbols.firstOrNull { it.name == "TestVS" }
        assertTrue(vs != null, "Expected symbol named 'TestVS'")
        assertEquals(SymbolKind.Enum, vs!!.kind)
    }

    @Test
    fun documentSymbol_fixture_codeIsEnumMember() {
        val params = DocumentSymbolParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/DocumentSymbolFixture.cql"))
        val symbols = provider.documentSymbol(params)
        val code = symbols.firstOrNull { it.name == "TestCode" }
        assertTrue(code != null, "Expected symbol named 'TestCode'")
        assertEquals(SymbolKind.EnumMember, code!!.kind)
    }

    @Test
    fun documentSymbol_fixture_conceptIsStruct() {
        val params = DocumentSymbolParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/DocumentSymbolFixture.cql"))
        val symbols = provider.documentSymbol(params)
        val concept = symbols.firstOrNull { it.name == "TestConcept" }
        assertTrue(concept != null, "Expected symbol named 'TestConcept'")
        assertEquals(SymbolKind.Struct, concept!!.kind)
    }

    @Test
    fun documentSymbol_fixture_defineIsVariable() {
        val params = DocumentSymbolParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/DocumentSymbolFixture.cql"))
        val symbols = provider.documentSymbol(params)
        val def = symbols.firstOrNull { it.name == "TestDefine" }
        assertTrue(def != null, "Expected symbol named 'TestDefine'")
        assertEquals(SymbolKind.Variable, def!!.kind)
    }
}
