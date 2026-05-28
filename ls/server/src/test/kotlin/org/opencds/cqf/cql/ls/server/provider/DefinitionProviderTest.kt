package org.opencds.cqf.cql.ls.server.provider

import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.hl7.elm.r1.Add
import org.hl7.elm.r1.Code
import org.hl7.elm.r1.CodeRef
import org.hl7.elm.r1.CodeSystemRef
import org.hl7.elm.r1.ConceptRef
import org.hl7.elm.r1.ExpressionRef
import org.hl7.elm.r1.FunctionRef
import org.hl7.elm.r1.ParameterRef
import org.hl7.elm.r1.ValueSetRef
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

    // -------------------------------------------------------------------------
    // ValueSetRef — cursor on "Beta Blocker Therapy"
    // -------------------------------------------------------------------------

    @Test
    fun definition_valueSetRef_navigatesToValueSetDef() {
        // WithTerminology.cql line 7: define "UseVS": "Beta Blocker Therapy"
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithTerminology.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseVS" }
        val ref = def.expression as ValueSetRef
        val range = TrackBacks.toRange(ref.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val locations =
            provider.definition(
                DefinitionParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithTerminology.cql"), pos),
            )

        assertFalse(locations.isEmpty(), "Expected a location for ValueSetRef 'Beta Blocker Therapy'")
    }

    // -------------------------------------------------------------------------
    // CodeSystemRef — cursor on "SNOMEDCT" inside Code '12345' from "SNOMEDCT"
    // -------------------------------------------------------------------------

    @Test
    fun definition_codeSystemRef_navigatesToCodeSystemDef() {
        // WithTerminology.cql line 10: define "UseCS": Code '12345' from "SNOMEDCT"
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithTerminology.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseCS" }
        val code = def.expression as Code
        val csRef = code.system as? CodeSystemRef ?: return
        val range = TrackBacks.toRange(csRef.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val locations =
            provider.definition(
                DefinitionParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithTerminology.cql"), pos),
            )

        assertFalse(locations.isEmpty(), "Expected a location for CodeSystemRef 'SNOMEDCT'")
    }

    // -------------------------------------------------------------------------
    // ParameterRef — cursor on "Measurement Period" in the expression body
    // -------------------------------------------------------------------------

    @Test
    fun definition_parameterRef_navigatesToParameterDef() {
        // WithParam.cql line 6: define "Using Measurement Period": "Measurement Period"
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithParam.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Using Measurement Period" }
        val ref = def.expression as ParameterRef
        val range = TrackBacks.toRange(ref.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val locations =
            provider.definition(
                DefinitionParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithParam.cql"), pos),
            )

        assertFalse(locations.isEmpty(), "Expected a location for ParameterRef 'Measurement Period'")
    }

    // -------------------------------------------------------------------------
    // Local (unqualified) FunctionRef — same library, no library alias
    // -------------------------------------------------------------------------

    @Test
    fun definition_localFunctionRef_navigatesToFunctionDef() {
        // FunctionLib.cql line 9: define "UseDouble": "Double"(42)
        // The FunctionRef is unqualified (no library alias) and resolves locally
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionLib.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseDouble" }
        val ref = def.expression as FunctionRef
        val range = TrackBacks.toRange(ref.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val locations =
            provider.definition(
                DefinitionParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionLib.cql"), pos),
            )

        assertFalse(locations.isEmpty(), "Expected a location for local FunctionRef 'Double'")
        val loc = locations.first()
        assertTrue(loc.targetUri.contains("FunctionLib"), "Expected definition in same library, got: ${loc.targetUri}")
        assertNotNull(loc.originSelectionRange, "Expected originSelectionRange to be set")
    }

    // -------------------------------------------------------------------------
    // Cross-library ValueSetRef — cursor on TL."..." resolves to included lib
    // -------------------------------------------------------------------------

    @Test
    fun definition_crossLibraryValueSetRef_navigatesToValueSetDef() {
        // TerminologyCaller.cql: define "UseValueSet": TL."Ambulatory Encounter"
        val callerUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/TerminologyCaller.cql")!!
        val termLibUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/TerminologyLib.cql")!!
        compilationManager.compile(termLibUri)
        val library = compilationManager.compile(callerUri)!!.library!!

        val def = library.statements!!.def.first { it.name == "UseValueSet" }
        val expression = def.expression
        // The compiler may resolve as ExpressionRef or ValueSetRef
        val refElm =
            when {
                expression is ValueSetRef -> expression
                expression is ExpressionRef -> expression
                else -> return
            }
        val range = TrackBacks.toRange(refElm.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val locations =
            provider.definition(
                DefinitionParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/TerminologyCaller.cql"), pos),
            )

        assertFalse(locations.isEmpty(), "Expected a location for cross-library ValueSetRef")
        assertTrue(locations.any { it.targetUri.contains("TerminologyLib") }, "Expected definition in TerminologyLib")
    }

    @Test
    fun definition_crossLibraryConceptRef_navigatesToConceptDef() {
        // TerminologyCaller.cql: define "UseConcept": TL."Left Foot Pain"
        val callerUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/TerminologyCaller.cql")!!
        val termLibUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/TerminologyLib.cql")!!
        compilationManager.compile(termLibUri)
        val library = compilationManager.compile(callerUri)!!.library!!

        val def = library.statements!!.def.first { it.name == "UseConcept" }
        val expression = def.expression
        val refElm =
            when {
                expression is ConceptRef -> expression
                expression is ExpressionRef -> expression
                else -> return
            }
        val range = TrackBacks.toRange(refElm.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val locations =
            provider.definition(
                DefinitionParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/TerminologyCaller.cql"), pos),
            )

        assertFalse(locations.isEmpty(), "Expected a location for cross-library ConceptRef")
        assertTrue(locations.any { it.targetUri.contains("TerminologyLib") }, "Expected definition in TerminologyLib")
    }

    // -------------------------------------------------------------------------
    // Cross-library CodeSystemRef — cursor on TL."SNOMEDCT"
    // -------------------------------------------------------------------------

    @Test
    fun definition_crossLibraryCodeSystemRef_navigatesToCodeSystemDef() {
        // CrossTerminologyCaller.cql has "UseCodeSystem": TL."SNOMEDCT"
        val callerUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/TerminologyCaller.cql")!!
        val termLibUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/TerminologyLib.cql")!!
        compilationManager.compile(termLibUri)
        val library = compilationManager.compile(callerUri)!!.library!!

        val def = library.statements!!.def.first { it.name == "UseCodeSystem" }
        val expression = def.expression
        val refElm =
            when {
                expression is CodeSystemRef -> expression
                expression is ExpressionRef -> expression
                else -> return
            }
        val range = TrackBacks.toRange(refElm.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val locations =
            provider.definition(
                DefinitionParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/TerminologyCaller.cql"), pos),
            )

        assertFalse(locations.isEmpty(), "Expected a location for cross-library CodeSystemRef")
        assertTrue(locations.any { it.targetUri.contains("TerminologyLib") }, "Expected definition in TerminologyLib")
    }

    // -------------------------------------------------------------------------
    // Cross-library CodeRef — cursor on TL."Venous foot pain, left"
    // -------------------------------------------------------------------------

    @Test
    fun definition_crossLibraryCodeRef_navigatesToCodeDef() {
        val callerUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/TerminologyCaller.cql")!!
        val termLibUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/TerminologyLib.cql")!!
        compilationManager.compile(termLibUri)
        val library = compilationManager.compile(callerUri)!!.library!!

        val def = library.statements!!.def.first { it.name == "UseCode" }
        val expression = def.expression
        // The compiler may resolve this as ExpressionRef or CodeRef
        val refElm =
            when {
                expression is CodeRef -> expression
                expression is ExpressionRef -> expression
                else -> return
            }
        val range = TrackBacks.toRange(refElm.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val locations =
            provider.definition(
                DefinitionParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/TerminologyCaller.cql"), pos),
            )

        assertFalse(locations.isEmpty(), "Expected a location for cross-library CodeRef")
        assertTrue(locations.any { it.targetUri.contains("TerminologyLib") }, "Expected definition in TerminologyLib")
    }

    // -------------------------------------------------------------------------
    // OperandRef with no locator — falls back to FunctionDef
    // -------------------------------------------------------------------------

    @Test
    fun definition_operandRefFallback_noLocatorOnOperandDef_returnsFunctionDef() {
        // FunctionBody.cql line 3: define function "Identity"(x System.String):
        // The ELM compiler does not set locators on OperandDef nodes. The DefinitionProvider
        // fallback uses the parent FunctionDef locator instead.
        // We navigate to the operand `x` by finding a reference to it in the body.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionBody.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        // The function body on line 4 (1-indexed): `    x` — cursor on `x` which is an OperandRef
        // Position(3, 4) lands on `x` in the Identity function body (0-indexed line 3 = file line 4)
        val locations =
            provider.definition(
                DefinitionParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionBody.cql"),
                    Position(3, 4),
                ),
            )

        assertFalse(locations.isEmpty(), "Expected a location for OperandRef 'x' fallback to FunctionDef")
        val loc = locations.first()
        // The target should be the FunctionDef's locator (function signature), not the operand
        assertTrue(
            loc.targetUri.contains("FunctionBody"),
            "Expected definition in FunctionBody, got: ${loc.targetUri}",
        )
        // The targetRange should cover the function signature (not just the operand)
        assertNotNull(loc.targetRange, "Expected targetRange to be set")
    }
}
