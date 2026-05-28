package org.opencds.cqf.cql.ls.server.provider

import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService

class CodeRefInsideRetrieveTest {
    companion object {
        private lateinit var hoverProvider: HoverProvider
        private lateinit var compilationManager: CqlCompilationManager

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val cs = TestContentService()
            compilationManager =
                CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
            hoverProvider = HoverProvider(compilationManager, cs)
        }
    }

    @Test
    fun compileTest() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/CodeRefInsideRetrieve.cql")!!
        val compiler = compilationManager.compile(uri)
        assertNotNull(compiler, "Expected compilation to succeed")
        val codeRef = compiler!!.compiledLibrary?.resolveCodeRef("Adult depression screening assessment")
        assertNotNull(codeRef, "Expected CodeDef to be resolvable")
    }

    @Test
    fun cursorClassifier_returnsExpressionRef_forCodeNameInsideRetrieve() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/CodeRefInsideRetrieve.cql")!!
        compilationManager.compile(uri)
        val parseTree = compilationManager.getParseTree(uri)!!

        // Line 13 (0-indexed): `  Last([Observation: "Adult depression screening assessment"] Obs`
        // Cursor on 'A' of "Adult depression screening assessment" at column 22
        val category = CursorClassifier.classify(parseTree, Position(13, 22))
        assertTrue(
            category is CursorCategory.ExpressionRef,
            "Expected ExpressionRef, got: ${category::class.simpleName} $category",
        )
        val er = category as CursorCategory.ExpressionRef
        println("ExpressionRef name: '${er.name}', libraryName: ${er.libraryName}")
        assertTrue(er.name.contains("Adult depression screening assessment"))
    }

    @Test
    fun hover_onCodeRefInsideRetrieve_returnsCodeDetails() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/CodeRefInsideRetrieve.cql")!!

        // Line 13 (0-indexed): `  Last([Observation: "Adult depression screening assessment"] Obs`
        // Cursor on 'A' of "Adult depression screening assessment" at column 22
        val hover =
            hoverProvider.hover(
                HoverParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/CodeRefInsideRetrieve.cql"),
                    Position(13, 22),
                ),
            )

        assertNotNull(hover, "Expected hover on code ref inside retrieve, got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("code"), "Expected CQL code syntax: $value")
        assertTrue(value.contains("Adult depression screening assessment"), "Expected code name: $value")
        assertTrue(value.contains("73832-8"), "Expected LOINC code: $value")
    }
}
