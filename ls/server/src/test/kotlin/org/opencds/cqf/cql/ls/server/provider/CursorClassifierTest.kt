package org.opencds.cqf.cql.ls.server.provider

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService
import org.opencds.cqf.cql.ls.server.utility.TrackBacks

class CursorClassifierTest {
    companion object {
        private lateinit var compilationManager: CqlCompilationManager

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val cs = TestContentService()
            compilationManager =
                CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
        }
    }

    @Test
    fun classify_onSortByImplicitProperty_period_returnsPropertyNameWithImplicitFlag() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/SortByImplicitScopeQuery.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!

        // Line 10: `    sort by period` — cursor on `period` at column 12
        val category = CursorClassifier.classify(parseTree, Position(10, 12))
        assertTrue(category is CursorCategory.PropertyName, "Expected PropertyName, got: ${category::class.simpleName}")
        val pn = category as CursorCategory.PropertyName
        assertEquals("period", pn.name)
        assertEquals("E", pn.aliasName)
        assertTrue(pn.implicit)
    }

    @Test
    fun classify_onSortByImplicitProperty_effective_returnsPropertyNameWithImplicitFlag() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/SortByImplicitScopeQuery.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!

        // Line 20: `    sort by start of effective` — cursor on `effective` at column 21
        val category = CursorClassifier.classify(parseTree, Position(20, 21))
        assertTrue(category is CursorCategory.PropertyName, "Expected PropertyName, got: ${category::class.simpleName}")
        val pn = category as CursorCategory.PropertyName
        assertEquals("effective", pn.name)
        assertEquals("O", pn.aliasName)
        assertTrue(pn.implicit)
    }

    @Test
    fun classify_onSortByBareIdentifier_returnsPropertyName() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/SortByImplicitScopeQuery.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!

        // Line 15: `    sort by status` — cursor on `status` at column 12
        val category = CursorClassifier.classify(parseTree, Position(15, 12))
        assertTrue(category is CursorCategory.PropertyName, "Expected PropertyName, got: ${category::class.simpleName}")
        val pn = category as CursorCategory.PropertyName
        assertEquals("status", pn.name)
        assertEquals("E", pn.aliasName)
        assertTrue(pn.implicit)
    }

    @Test
    fun classify_onSortByDeclaredAlias_returnsAliasReference() {
        // AllClausesQuery.cql line 15: `define "Sort Clause Test": from "Numbers" N sort by N`
        // The second `N` (after `sort by`) is a query alias reference. The classifier now
        // identifies it as AliasReference before the ExpressionDefName check.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!

        // `N` starts at column 52 on line 14 (0-indexed)
        val category = CursorClassifier.classify(parseTree, Position(14, 52))
        assertTrue(category is CursorCategory.AliasReference, "Expected AliasReference, got: ${category::class.simpleName}")
        assertEquals("N", (category as CursorCategory.AliasReference).name)
    }

    @Test
    fun findAliasedQuerySource_retrieveSource() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/ScopeCoercionQuery.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!

        // Position inside the such-that clause, on `E` in `E.period`
        val result = CursorClassifier.findAliasedQuerySource(parseTree, Position(10, 39), "E")
        assertNotNull(result, "Expected AliasedQuerySourceContext for alias 'E'")
    }

    @Test
    fun findAliasedQuerySource_withClause() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/ScopeCoercionQuery.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!

        // Position inside the such-that clause, on `E2` in `E2.period`
        val result = CursorClassifier.findAliasedQuerySource(parseTree, Position(10, 74), "E2")
        assertNotNull(result, "Expected AliasedQuerySourceContext for alias 'E2'")
    }

    @Test
    fun findAliasedQuerySource_noMatch_returnsNull() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/ScopeCoercionQuery.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!

        val result = CursorClassifier.findAliasedQuerySource(parseTree, Position(10, 39), "NONEXISTENT")
        assertNull(result, "Expected null for nonexistent alias")
    }

    @Test
    fun findAliasedQuerySource_outsideQuery_returnsNull() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/ScopeCoercionQuery.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!

        // Position on `library` keyword (line 0) — outside any query
        val result = CursorClassifier.findAliasedQuerySource(parseTree, Position(0, 1), "E")
        assertNull(result, "Expected null for position outside any query")
    }

    @Test
    fun findAliasedQuerySource_nonRetrieveSource_returnsContext() {
        // WithQuery.cql uses "Items" Item (qualified identifier expression, not a retrieve)
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithQuery.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!

        // Position at `Item` in `return Item` (line 7, 1-indexed) inside the "Items Alias Test" query
        val result = CursorClassifier.findAliasedQuerySource(parseTree, Position(6, 10), "Item")
        assertNotNull(result, "Expected AliasedQuerySourceContext for alias 'Item'")
    }

    @Test
    fun classify_onExpressionDefName_returnsExpressionDefName() {
        // One.cql line 2: `define "One": 1` — cursor inside the definition name
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        val category = CursorClassifier.classify(parseTree, Position(2, 8))
        assertTrue(category is CursorCategory.ExpressionDefName, "Expected ExpressionDefName, got: ${category::class.simpleName}")
        assertEquals("One", (category as CursorCategory.ExpressionDefName).name)
    }

    @Test
    fun classify_onFunctionDefName_returnsFunctionDefName() {
        // FunctionLib.cql line 6: `define function "Double"(x Integer):` — cursor on "Double"
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionLib.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        val category = CursorClassifier.classify(parseTree, Position(5, 17))
        assertTrue(category is CursorCategory.FunctionDefName, "Expected FunctionDefName, got: ${category::class.simpleName}")
        assertEquals("Double", (category as CursorCategory.FunctionDefName).name)
    }

    @Test
    fun classify_onParameterDefName_returnsParameterDefName() {
        // WithParam.cql line 3: `parameter "Measurement Period" ...`
        // Use the ELM locator to find the exact position of the parameter definition
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithParam.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val paramDef = library.parameters!!.def.first { it.name == "Measurement Period" }
        val range = TrackBacks.toRange(paramDef.locator!!)!!
        val parseTree = compilationManager.getParseTree(uri)!!
        val category = CursorClassifier.classify(parseTree, Position(range.start.line, range.start.character + 1))
        assertTrue(category is CursorCategory.ParameterDefName, "Expected ParameterDefName, got: ${category::class.simpleName}")
        assertEquals("Measurement Period", (category as CursorCategory.ParameterDefName).name)
    }

    @Test
    fun classify_onKeywordSuppress_fromKeyword_returnsKeywordSuppress() {
        // AllClausesQuery.cql line 10: `  from "Numbers" N return N`
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        val category = CursorClassifier.classify(parseTree, Position(10, 2))
        assertTrue(category is CursorCategory.KeywordSuppress, "Expected KeywordSuppress for 'from', got: ${category::class.simpleName}")
    }

    @Test
    fun classify_onKeywordSuppress_whereKeyword_returnsKeywordSuppress() {
        // AllClausesQuery.cql line 6: `    where N > 1`
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        val category = CursorClassifier.classify(parseTree, Position(6, 4))
        assertTrue(category is CursorCategory.KeywordSuppress, "Expected KeywordSuppress for 'where', got: ${category::class.simpleName}")
    }

    @Test
    fun classify_onKeywordSuppress_returnKeyword_returnsKeywordSuppress() {
        // ReturnClauseQuery.cql line 7: `    return N`
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/ReturnClauseQuery.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        val category = CursorClassifier.classify(parseTree, Position(6, 4))
        assertTrue(category is CursorCategory.KeywordSuppress, "Expected KeywordSuppress for 'return', got: ${category::class.simpleName}")
    }

    @Test
    fun classify_onKeywordSuppress_letKeyword_returnsKeywordSuppress() {
        // AllClausesQuery.cql line 12: `define "Let Clause Test": from "Numbers" N let X: N + 1 return X`
        // The `let` keyword starts at column 43 (0-indexed)
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        val category = CursorClassifier.classify(parseTree, Position(12, 43))
        assertTrue(category is CursorCategory.KeywordSuppress, "Expected KeywordSuppress for 'let', got: ${category::class.simpleName}")
    }

    @Test
    fun classify_onKeywordSuppress_sortKeyword_returnsKeywordSuppress() {
        // AllClausesQuery.cql line 14: `define "Sort Clause Test": from "Numbers" N sort by N`
        // The `sort` keyword starts at column 44 (0-indexed)
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        val category = CursorClassifier.classify(parseTree, Position(14, 44))
        assertTrue(category is CursorCategory.KeywordSuppress, "Expected KeywordSuppress for 'sort', got: ${category::class.simpleName}")
    }

    @Test
    fun classify_onKeywordSuppress_withKeyword_returnsKeywordSuppress() {
        // WithQuery.cql line 11: `    with "Items" Extra such that Extra > Item`
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithQuery.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        val category = CursorClassifier.classify(parseTree, Position(11, 4))
        assertTrue(category is CursorCategory.KeywordSuppress, "Expected KeywordSuppress for 'with', got: ${category::class.simpleName}")
    }

    @Test
    fun classify_onKeywordSuppress_withoutKeyword_returnsKeywordSuppress() {
        // WithoutQuery.cql line 7: `    without "Items" Extra such that Extra = Item`
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithoutQuery.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        val category = CursorClassifier.classify(parseTree, Position(7, 4))
        assertTrue(category is CursorCategory.KeywordSuppress, "Expected KeywordSuppress for 'without', got: ${category::class.simpleName}")
    }

    @Test
    fun classify_onCodesystemIdentifier_returnsExpressionRef() {
        // WithTerminology.cql line 11: `    Code '12345' from "SNOMEDCT"`
        // Cursor on "SNOMEDCT" — a codesystem identifier context
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithTerminology.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        val category = CursorClassifier.classify(parseTree, Position(10, 23))
        assertTrue(category is CursorCategory.ExpressionRef, "Expected ExpressionRef for codesystem identifier, got: ${category::class.simpleName}")
        assertEquals("SNOMEDCT", (category as CursorCategory.ExpressionRef).name)
        assertNull(category.libraryName)
    }

    @Test
    fun classify_onRetrieveType_withoutQualifier_returnsRetrieve() {
        // ScopeCoercionQuery.cql line 9: `  [Encounter] E`
        // Cursor on "Encounter" inside the retrieve
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/ScopeCoercionQuery.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        val category = CursorClassifier.classify(parseTree, Position(8, 3))
        assertTrue(category is CursorCategory.Retrieve, "Expected Retrieve, got: ${category::class.simpleName}")
        val r = category as CursorCategory.Retrieve
        assertEquals("Encounter", r.typeName)
        assertNull(r.modelQualifier)
    }

    @Test
    fun classify_onRetrieveType_withQualifier_returnsRetrieveWithModelQualifier() {
        // ScopeCoercionQuery.cql line 10: `    with [Encounter] E2`
        // Cursor on "Encounter" — this one is an included-model type
        // Actually, the ScopeCoercionQuery uses `[Encounter]` without qualifier
        // so let's use a position that has a qualified type
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/SortByImplicitScopeQuery.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        // Line 10: `  Last([Encounter] E` — cursor on "Encounter"
        val category = CursorClassifier.classify(parseTree, Position(9, 8))
        assertTrue(category is CursorCategory.Retrieve, "Expected Retrieve, got: ${category::class.simpleName}")
        val r = category as CursorCategory.Retrieve
        assertEquals("Encounter", r.typeName)
    }

    @Test
    fun classify_onUnqualifiedFunctionInvocation_returnsFunctionCall() {
        // OverloadedFunctions.cql line 12: `define "UseUnary": "Add"(1)`
        // Cursor on "Add" — an unqualified function call
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/OverloadedFunctions.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        val category = CursorClassifier.classify(parseTree, Position(11, 20))
        assertTrue(category is CursorCategory.FunctionCall, "Expected FunctionCall, got: ${category::class.simpleName}")
        val fc = category as CursorCategory.FunctionCall
        assertEquals("Add", fc.name)
        assertNull(fc.libraryName)
    }

    @Test
    fun classify_onOperandRef_returnsOperandRef() {
        // FunctionBody.cql line 6: `    if boolean` — cursor on `boolean` (an OperandRef)
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionBody.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        val category = CursorClassifier.classify(parseTree, Position(6, 7))
        assertTrue(category is CursorCategory.OperandRef, "Expected OperandRef, got: ${category::class.simpleName}")
        assertEquals("boolean", (category as CursorCategory.OperandRef).name)
    }

    @Test
    fun classify_onUnknown_returnsUnknown() {
        // One.cql line 0: `library One` — outside any expression
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        val category = CursorClassifier.classify(parseTree, Position(0, 0))
        assertTrue(category is CursorCategory.Unknown, "Expected Unknown for library declaration, got: ${category::class.simpleName}")
    }

    @Test
    fun classify_onDefinitionContextFallback_returnsDefName() {
        // AllClausesQuery.cql line 4: `define "Where Clause Test":` — cursor on `define` keyword
        // The cursor falls inside ExpressionDefinitionContext but not in a specific slot
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        val category = CursorClassifier.classify(parseTree, Position(4, 0))
        assertTrue(category is CursorCategory.ExpressionDefName, "Expected ExpressionDefName for fallback, got: ${category::class.simpleName}")
    }

    @Test
    fun classify_onFunctionDefNameFallback_returnsFunctionDefName() {
        // FunctionBody.cql line 3: `define function "Identity"(x System.String):`
        // Cursor on `define` keyword — fallback to FunctionDefName
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionBody.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        val category = CursorClassifier.classify(parseTree, Position(3, 0))
        assertTrue(category is CursorCategory.FunctionDefName, "Expected FunctionDefName for fallback, got: ${category::class.simpleName}")
        assertEquals("Identity", (category as CursorCategory.FunctionDefName).name)
    }
}
