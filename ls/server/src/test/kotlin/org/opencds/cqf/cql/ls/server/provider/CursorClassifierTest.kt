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
}
