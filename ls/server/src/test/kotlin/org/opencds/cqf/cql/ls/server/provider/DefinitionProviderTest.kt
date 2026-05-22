package org.opencds.cqf.cql.ls.server.provider

import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.hl7.elm.r1.Add
import org.hl7.elm.r1.ExpressionRef
import org.hl7.elm.r1.FunctionRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import org.opencds.cqf.cql.ls.server.utility.TrackBacks

class DefinitionProviderTest {
    companion object {
        private lateinit var provider: DefinitionProvider
        private lateinit var compilationManager: CqlCompilationManager

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val cs = TestContentService()
            compilationManager =
                CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
            provider = DefinitionProvider(compilationManager, cs)
        }
    }

    // -------------------------------------------------------------------------
    // Cross-library: ExpressionRef (FL."MyValue")
    // -------------------------------------------------------------------------

    @Test
    fun definition_crossLibraryExpressionRef_navigatesToDefinitionInIncludedLibrary() {
        val callerUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!
        val library = compilationManager.compile(callerUri)!!.library!!

        // Locate the ExpressionRef for FL."MyValue" inside the "UseValue" def
        val useValueDef = library.statements!!.def.first { it.name == "UseValue" }
        // The expression is an Add; operand[0] is the ExpressionRef to FL."MyValue"
        val ref = (useValueDef.expression as Add).operand[0] as ExpressionRef
        val range = TrackBacks.toRange(ref.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val locations =
            provider.definition(
                DefinitionParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql"), pos),
            )

        assertFalse(locations.isEmpty(), "Expected at least one location")
        val loc = locations.first()
        assertTrue(loc.targetUri.contains("FunctionLib"), "Expected definition in FunctionLib, got: ${loc.targetUri}")
        // originSelectionRange should cover the position used to trigger the request
        assertNotNull(loc.originSelectionRange, "Expected originSelectionRange to be set")
        val origin = loc.originSelectionRange!!
        assertTrue(
            origin.start.line <= pos.line && pos.line <= origin.end.line,
            "Expected originSelectionRange to contain the trigger position",
        )
    }

    // -------------------------------------------------------------------------
    // Cross-library: FunctionRef (FL."Double"(3))
    // -------------------------------------------------------------------------

    @Test
    fun definition_crossLibraryFunctionRef_navigatesToFunctionDefinitionInIncludedLibrary() {
        val callerUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!
        val library = compilationManager.compile(callerUri)!!.library!!

        val useFunctionDef = library.statements!!.def.first { it.name == "UseFunction" }
        val ref = useFunctionDef.expression as FunctionRef
        val range = TrackBacks.toRange(ref.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val locations =
            provider.definition(
                DefinitionParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql"), pos),
            )

        assertFalse(locations.isEmpty(), "Expected at least one location")
        val loc = locations.first()
        assertTrue(loc.targetUri.contains("FunctionLib"), "Expected definition in FunctionLib, got: ${loc.targetUri}")
        // originSelectionRange should cover the position used to trigger the request
        assertNotNull(loc.originSelectionRange, "Expected originSelectionRange to be set")
        val origin = loc.originSelectionRange!!
        assertTrue(
            origin.start.line <= pos.line && pos.line <= origin.end.line,
            "Expected originSelectionRange to contain the trigger position",
        )
    }

    // -------------------------------------------------------------------------
    // IncludeDef: cursor on "include FunctionLib"
    // -------------------------------------------------------------------------

    @Test
    fun definition_includeDef_navigatesToTopOfIncludedFile() {
        val callerUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!
        val library = compilationManager.compile(callerUri)!!.library!!

        val includeDef = library.includes!!.def.first { it.localIdentifier == "FL" }
        val range = TrackBacks.toRange(includeDef.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val locations =
            provider.definition(
                DefinitionParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql"), pos),
            )

        assertFalse(locations.isEmpty(), "Expected at least one location for IncludeDef")
        val loc = locations.first()
        assertTrue(loc.targetUri.contains("FunctionLib"), "Expected navigation to FunctionLib, got: ${loc.targetUri}")
        // Should navigate to the very top of the file (line 0)
        assertEquals(0, loc.targetRange.start.line, "IncludeDef should navigate to line 0 of the included file")
        // originSelectionRange should be set (covers the include directive)
        assertNotNull(loc.originSelectionRange, "Expected originSelectionRange to be set for IncludeDef")
    }

    // -------------------------------------------------------------------------
    // Literal argument in function call — should return empty
    // -------------------------------------------------------------------------

    @Test
    fun definition_literalArgument_returnsEmpty() {
        // FunctionBody.cql line 11 (0-indexed): "    \"Identity\"('Hello')"
        // Position(11, 15) lands on 'H' inside 'Hello' — a Literal argument.
        // "Go to Definition" should return nothing for a literal.
        val locations =
            provider.definition(
                DefinitionParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionBody.cql"),
                    Position(11, 15),
                ),
            )

        assertTrue(locations.isEmpty(), "Expected empty locations for Literal argument, got ${locations.size}")
    }

    // -------------------------------------------------------------------------
    // OperandRef in function body — should navigate to the OperandDef
    // -------------------------------------------------------------------------

    @Test
    fun definition_operandRef_navigatesToOperandDef() {
        // FunctionBody.cql line 6 (0-indexed): "    if boolean"
        // Position(6, 7) lands on 'b' of "boolean" (OperandRef inside Denied Reason body).
        // "Go to Definition" should navigate to the OperandDef "boolean" in the signature.
        val locations =
            provider.definition(
                DefinitionParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionBody.cql"),
                    Position(6, 7),
                ),
            )

        assertFalse(locations.isEmpty(), "Expected a location for OperandRef")
        val loc = locations.first()
        assertTrue(
            loc.targetUri.contains("FunctionBody"),
            "Expected definition in FunctionBody, got: ${loc.targetUri}",
        )
        assertNotNull(loc.originSelectionRange, "Expected originSelectionRange to be set")
    }

    // -------------------------------------------------------------------------
    // Outside any navigable element — should return empty list
    // -------------------------------------------------------------------------

    @Test
    fun definition_outsideAnyElement_returnsEmpty() {
        // Position(0, 0) is on "library FunctionCaller" — not a navigable ref
        val locations =
            provider.definition(
                DefinitionParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql"),
                    Position(0, 0),
                ),
            )
        assertTrue(locations.isEmpty(), "Expected empty locations for non-navigable position")
    }
}
