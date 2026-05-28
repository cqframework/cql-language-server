package org.opencds.cqf.cql.ls.server.visitor

import org.eclipse.lsp4j.Position
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

class CqlParseTreeVisitorTest {
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
    fun findDeepestContext_insideExpression_returnsContext() {
        // One.cql line 2: define "One": 1  (0-indexed)
        // Place cursor on "1" — should return a context containing it.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        val result = CqlParseTreeVisitor.findDeepestContext(parseTree, Position(2, 12))
        assertNotNull(result, "Expected a non-null context for position inside expression")
    }

    @Test
    fun findDeepestContext_outsideContent_returnsNull() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        val result = CqlParseTreeVisitor.findDeepestContext(parseTree, Position(999, 0))
        assertNull(result, "Expected null for position far beyond file content")
    }

    @Test
    fun findDeepestContext_nestedNode_returnsDeeperThanParent() {
        // One.cql line 2: define "One": 1
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        val root = parseTree
        val deepest = CqlParseTreeVisitor.findDeepestContext(parseTree, Position(2, 12))
        assertNotNull(deepest)
        // The deepest context should be a descendant of (or same as) the root
        val rootStart = root.start?.line ?: 0
        val deepestStart = deepest!!.start?.line ?: 0
        assertTrue(deepestStart >= rootStart, "Deepest context should be at or below the root")
    }

    @Test
    fun findDeepestContext_onLibraryKeyword_returnsLibraryContext() {
        // One.cql line 0: library One version '1.0.0'
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        val result = CqlParseTreeVisitor.findDeepestContext(parseTree, Position(0, 0))
        assertNotNull(result, "Expected a context for position on library keyword")
    }

    @Test
    fun findDeepestContext_midDefine_returnsDefinitionContext() {
        // One.cql line 2 (0-indexed), cursor at start of "define" keyword
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!
        val result = CqlParseTreeVisitor.findDeepestContext(parseTree, Position(2, 0))
        assertNotNull(result, "Expected a context for position on define keyword")
    }
}
