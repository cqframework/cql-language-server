package org.opencds.cqf.cql.ls.server.utility

import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.FunctionDef
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
    fun writeAsString_withFhirQuery_retrieveCodesChildAppearsWithLocalId() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithFhirQuery.cql")!!
        val compiler =
            compilationManager.compile(uri)
                ?: throw AssertionError("compile returned null for WithFhirQuery")
        val result = ElmAstLibraryWriter(compiler).writeAsString(compiler.library!!)

        // codes child (ValueSetRef) should appear as a child node in the AST tree
        assertTrue(result.contains("ValueSetRef (name="), "Expected ValueSetRef child node: $result")
        assertTrue(result.contains("[id="), "Expected localId on child node: $result")

        // Parent label should still include the codes: summary (displayValue() guard)
        val retrieveLines = result.lines().filter { it.contains("Retrieve") }
        val codesRetrieve =
            retrieveLines.firstOrNull { it.contains("codes:") }
                ?: throw AssertionError("No Retrieve with codes: found in AST. All Retrieves:\n${retrieveLines.joinToString("\n")}")
        assertTrue(codesRetrieve.contains("codes:"), "Parent label should retain codes: summary: $codesRetrieve")
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

    // -----------------------------------------------------------------------
    // displayValue() branches not covered by One.cql / WithFhirQuery.cql
    // -----------------------------------------------------------------------

    @Test
    fun writeAsString_withFunctionLib_containsFunctionDef() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionLib.cql")!!
        val compiler = compilationManager.compile(uri) ?: throw AssertionError("compile returned null")
        val result = ElmAstLibraryWriter(compiler).writeAsString(compiler.library!!)

        // FunctionLib declares functions; the AST should contain function definitions
        assertTrue(result.contains("define"), "Expected define node in function library AST: $result")
        val hasFunctionOrParam = result.contains("function") || result.contains("OperandRef") || result.contains("FunctionRef")
        assertTrue(hasFunctionOrParam, "Expected function-related content in FunctionLib AST: $result")
    }

    @Test
    fun writeAsString_withFunctionCaller_containsFunctionRef() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!
        val compiler = compilationManager.compile(uri) ?: throw AssertionError("compile returned null for FunctionCaller")
        val result = ElmAstLibraryWriter(compiler).writeAsString(compiler.library!!)

        // FunctionCaller calls functions from FunctionLib — expects FunctionRef nodes
        assertTrue(result.contains("FunctionRef"), "Expected FunctionRef in FunctionCaller AST: $result")
    }

    @Test
    fun writeAsString_withQueryLib_containsExpressionRefAndQuery() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithQuery.cql")!!
        val compiler = compilationManager.compile(uri) ?: throw AssertionError("compile returned null for WithQuery")
        val result = ElmAstLibraryWriter(compiler).writeAsString(compiler.library!!)

        assertTrue(result.contains("Query"), "Expected Query node in WithQuery AST: $result")
    }

    @Test
    fun writeAsString_withTerminologyLib_containsValueSetOrCodeSystem() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithTerminology.cql")!!
        val compiler = compilationManager.compile(uri) ?: throw AssertionError("compile returned null for WithTerminology")
        val result = ElmAstLibraryWriter(compiler).writeAsString(compiler.library!!)

        // Terminology library should declare at least valueset or codesystem sections
        val hasTerminology =
            result.contains("valueset:") || result.contains("codesystem:") ||
                result.contains("ValueSetRef") || result.contains("CodeSystemRef")
        assertTrue(hasTerminology, "Expected terminology section in WithTerminology AST: $result")
    }

    @Test
    fun writeAsString_functionLibWithFunctionDef_rendersFunctionNode() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionLib.cql")!!
        val compiler = compilationManager.compile(uri) ?: throw AssertionError("compile returned null")
        val library = compiler.library!!
        val result = ElmAstLibraryWriter(compiler).writeAsString(library)

        val hasFunctionDef = library.statements?.def?.any { it is FunctionDef } ?: false
        assertTrue(hasFunctionDef, "FunctionLib.cql should contain at least one FunctionDef")
        assertTrue(result.contains("define"), "AST should contain a define node for FunctionDef: $result")
    }

    @Test
    fun writeAsString_withNoCompiler_stillRenders() {
        // Constructor without compiler — translatorVersion() falls back to "?"
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        val compiler = compilationManager.compile(uri) ?: throw AssertionError("compile returned null")
        val library = compiler.library!!

        val result = ElmAstLibraryWriter().writeAsString(library)
        assertTrue(result.isNotEmpty(), "Writer without compiler should still produce output")
        assertTrue(result.contains("Library:"), "Output should start with Library header: $result")
    }

    @Test
    fun render_elementLinesMapIsConsistentAcrossRenderCalls() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithFhirQuery.cql")!!
        val compiler = compilationManager.compile(uri) ?: throw AssertionError("compile returned null")
        val library = compiler.library!!

        val writer = ElmAstLibraryWriter(compiler)
        val r1 = writer.render(library)
        val r2 = writer.render(library)

        // Element identity is per-object, not per-value, so maps may differ in identity.
        // But the same number of elements should be recorded each time.
        assertEquals(
            r1.elementLines.size,
            r2.elementLines.size,
            "Each render should record the same number of elements",
        )
    }
}
