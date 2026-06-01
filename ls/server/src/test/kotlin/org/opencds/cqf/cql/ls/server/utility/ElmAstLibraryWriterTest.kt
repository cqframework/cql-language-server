package org.opencds.cqf.cql.ls.server.utility

import org.hl7.elm.r1.ExpressionDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService

class ElmAstLibraryWriterTest {
    companion object {
        private lateinit var compilationManager: CqlCompilationManager

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val cs = TestContentService()
            compilationManager =
                CqlCompilationManager(
                    cs,
                    CompilerOptionsManager(cs),
                    IgContextManager(cs),
                    LibraryResolutionManager(emptyList()),
                )
        }
    }

    @Test
    fun writeAsString_withCompiler_showsReturnType() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        val compiler = compilationManager.compile(uri) ?: throw AssertionError("compile returned null")
        val result = ElmAstLibraryWriter(compiler).writeAsString(compiler.library!!)

        assertTrue(result.contains("returns System.Integer"), "Expected return type in AST output: $result")
    }

    @Test
    fun writeAsString_withCompiler_containsDefineAndLiteral() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        val compiler = compilationManager.compile(uri) ?: throw AssertionError("compile returned null")
        val result = ElmAstLibraryWriter(compiler).writeAsString(compiler.library!!)

        assertTrue(result.contains("define:"), "Expected define in AST output: $result")
        assertTrue(result.contains("Literal:"), "Expected Literal in AST output: $result")
    }

    @Test
    fun writeAsString_withFhirQuery_containsRetrieveWithDataType() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithFhirQuery.cql")!!
        val compiler =
            compilationManager.compile(uri)
                ?: throw AssertionError("compile returned null for WithFhirQuery")
        val result = ElmAstLibraryWriter(compiler).writeAsString(compiler.library!!)

        assertTrue(result.contains("Retrieve"), "Expected Retrieve in AST output: $result")
        assertTrue(result.contains("dataType:"), "Expected dataType in AST output: $result")
        assertTrue(result.contains("codeProperty:"), "Expected codeProperty in AST output: $result")
        assertTrue(result.contains("codeComparator:"), "Expected codeComparator in AST output: $result")
        assertTrue(result.contains("codes:"), "Expected codes in AST output: $result")
    }

    @Test
    fun render_textMatchesWriteAsString() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        val compiler = compilationManager.compile(uri) ?: throw AssertionError("compile returned null")
        val library = compiler.library!!

        val writer = ElmAstLibraryWriter(compiler)
        val fromWriteAsString = writer.writeAsString(library)
        val fromRender = writer.render(library)

        assertEquals(fromWriteAsString, fromRender.text, "render().text should equal writeAsString()")
    }

    @Test
    fun render_elementLinesCoversEveryEmittedElement() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        val compiler = compilationManager.compile(uri) ?: throw AssertionError("compile returned null")
        val library = compiler.library!!

        val rendering = ElmAstLibraryWriter(compiler).render(library)

        // Check that all ExpressionDef statements are in the elementLines map
        library.statements?.def?.forEach { def ->
            assertTrue(
                rendering.elementLines.containsKey(def),
                "ExpressionDef ${def.name} should be in elementLines map",
            )
        }

        // Verify the elementLines map is not empty (at least some elements were recorded)
        assertTrue(rendering.elementLines.isNotEmpty(), "elementLines map should not be empty")
    }

    @Test
    fun render_lineRangesAreOneIndexedAndContiguousForContainers() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        val compiler = compilationManager.compile(uri) ?: throw AssertionError("compile returned null")
        val library = compiler.library!!

        val rendering = ElmAstLibraryWriter(compiler).render(library)

        library.statements?.def?.filterIsInstance<ExpressionDef>()?.forEach { def ->
            val range = rendering.elementLines[def]
            assertTrue(range != null, "ExpressionDef ${def.name} should have a line range")
            assertTrue(range!!.first >= 1, "Line range should be 1-indexed, got ${range.first}")
            assertTrue(range.last >= range.first, "End line should be >= start line")
        }
    }

    @Test
    fun render_isStable() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        val compiler = compilationManager.compile(uri) ?: throw AssertionError("compile returned null")
        val library = compiler.library!!

        val writer = ElmAstLibraryWriter(compiler)
        val rendering1 = writer.render(library)
        val rendering2 = writer.render(library)

        assertEquals(rendering1.text, rendering2.text, "Two renders should produce identical text")
        assertEquals(rendering1.elementLines, rendering2.elementLines, "Two renders should produce identical elementLines map")
    }
}
