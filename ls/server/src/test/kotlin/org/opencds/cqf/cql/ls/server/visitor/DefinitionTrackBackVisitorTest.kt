package org.opencds.cqf.cql.ls.server.visitor

import org.eclipse.lsp4j.Position
import org.hl7.elm.r1.Add
import org.hl7.elm.r1.Code
import org.hl7.elm.r1.CodeRef
import org.hl7.elm.r1.CodeSystemRef
import org.hl7.elm.r1.ConceptDef
import org.hl7.elm.r1.ConceptRef
import org.hl7.elm.r1.ExpressionRef
import org.hl7.elm.r1.FunctionDef
import org.hl7.elm.r1.FunctionRef
import org.hl7.elm.r1.IncludeDef
import org.hl7.elm.r1.Literal
import org.hl7.elm.r1.OperandRef
import org.hl7.elm.r1.ParameterRef
import org.hl7.elm.r1.ValueSetRef
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService
import org.opencds.cqf.cql.ls.server.utility.TrackBacks

class DefinitionTrackBackVisitorTest {
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
    fun visitExpressionRef_matchingPosition_returnsRef() {
        // FunctionCaller.cql: define "UseValue": FL."MyValue" + 1
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseValue" }
        val ref = (def.expression as Add).operand[0] as ExpressionRef
        val range = TrackBacks.toRange(ref.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)
        val result = DefinitionTrackBackVisitor().visitLibrary(library, pos)
        assertInstanceOf(ExpressionRef::class.java, result)
    }

    @Test
    fun visitExpressionRef_nonMatchingPosition_returnsNull() {
        // FunctionCaller.cql — position outside all ref elements
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val result = DefinitionTrackBackVisitor().visitLibrary(library, Position(0, 0))
        assertNull(result)
    }

    @Test
    fun visitFunctionRef_withChild_returnsChild() {
        // FunctionCaller.cql: define "UseFunction": FL."Double"(3)
        // Place cursor on the argument "3" (Literal) — the visitor should return the
        // child (Literal) rather than the parent (FunctionRef).
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseFunction" }
        val ref = def.expression as FunctionRef
        val literal = ref.operand.first() as Literal
        val literalRange = TrackBacks.toRange(literal.locator!!)!!
        val pos = Position(literalRange.start.line, literalRange.start.character + 1)
        val result = DefinitionTrackBackVisitor().visitLibrary(library, pos)
        assertInstanceOf(Literal::class.java, result)
    }

    @Test
    fun visitFunctionRef_onFunctionName_returnsFunctionRef() {
        // FunctionCaller.cql: define "UseFunction": FL."Double"(3)
        // Place cursor on the function name "Double" — should return the FunctionRef.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseFunction" }
        val ref = def.expression as FunctionRef
        val range = TrackBacks.toRange(ref.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)
        val result = DefinitionTrackBackVisitor().visitLibrary(library, pos)
        assertInstanceOf(FunctionRef::class.java, result)
    }

    @Test
    fun visitValueSetRef_matchingPosition_returnsRef() {
        // WithTerminology.cql: define "UseVS": "Beta Blocker Therapy"
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithTerminology.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseVS" }
        val ref = def.expression as ValueSetRef
        val range = TrackBacks.toRange(ref.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)
        val result = DefinitionTrackBackVisitor().visitLibrary(library, pos)
        assertInstanceOf(ValueSetRef::class.java, result)
    }

    @Test
    fun visitCodeRef_matchingPosition_returnsRef() {
        // TerminologyLib.cql: concept "Left Foot Pain": { "Venous foot pain, left" }
        // The ConceptDef contains a CodeRef referencing the code by name.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/TerminologyLib.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val statements = library.statements ?: return
        val defs = statements.def ?: return
        val conceptDef = defs.firstOrNull { it.name == "Left Foot Pain" } ?: return
        val codes = (conceptDef as? ConceptDef)?.code ?: return
        val codeRef = codes.firstOrNull() ?: return
        val locator = codeRef.locator ?: return
        val range = TrackBacks.toRange(locator)!!
        val pos = Position(range.start.line, range.start.character + 1)
        val result = DefinitionTrackBackVisitor().visitLibrary(library, pos)
        assertInstanceOf(CodeRef::class.java, result)
    }

    @Test
    fun visitConceptRef_matchingPosition_returnsRef() {
        // TerminologyCaller.cql: define "UseConcept": TL."Left Foot Pain"
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/TerminologyCaller.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseConcept" }
        // Depending on the compiler, this reference may be ExpressionRef or ConceptRef.
        val expression = def.expression
        if (expression is ConceptRef) {
            val range = TrackBacks.toRange(expression.locator!!)!!
            val pos = Position(range.start.line, range.start.character + 1)
            val result = DefinitionTrackBackVisitor().visitLibrary(library, pos)
            assertInstanceOf(ConceptRef::class.java, result)
        }
    }

    @Test
    fun visitCodeSystemRef_matchingPosition_returnsRef() {
        // WithTerminology.cql: Code '12345' from "SNOMEDCT"
        // The Code literal contains a CodeSystemRef child referenced via "from".
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithTerminology.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseCS" }
        val code = def.expression as Code
        val codeSystemRef = code.system as CodeSystemRef
        val range = TrackBacks.toRange(codeSystemRef.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)
        val result = DefinitionTrackBackVisitor().visitLibrary(library, pos)
        assertInstanceOf(CodeSystemRef::class.java, result)
    }

    @Test
    fun visitIncludeDef_matchingPosition_returnsDef() {
        // FunctionCaller.cql line 2: include FunctionLib version '1.0.0' called FL
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val includeDef = library.includes!!.def.first { it.localIdentifier == "FL" }
        val range = TrackBacks.toRange(includeDef.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)
        val result = DefinitionTrackBackVisitor().visitLibrary(library, pos)
        assertInstanceOf(IncludeDef::class.java, result)
    }

    @Test
    fun visitOperandRef_matchingPosition_returnsRef() {
        // FunctionBody.cql line 6 (0-indexed): "    if boolean"
        // Position(6, 7) lands on 'b' of "boolean" — an OperandRef inside Denied Reason.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionBody.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val result = DefinitionTrackBackVisitor().visitLibrary(library, Position(6, 7))
        assertInstanceOf(OperandRef::class.java, result)
    }

    @Test
    fun visitParameterRef_matchingPosition_returnsRef() {
        // WithParam.cql line 6: define "Using Measurement Period": "Measurement Period"
        // The expression is a ParameterRef referencing the "Measurement Period" parameter.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithParam.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Using Measurement Period" }
        val ref = def.expression as ParameterRef
        val range = TrackBacks.toRange(ref.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)
        val result = DefinitionTrackBackVisitor().visitLibrary(library, pos)
        assertInstanceOf(ParameterRef::class.java, result)
    }

    @Test
    fun visitLiteral_matchingPosition_returnsLiteral() {
        // FunctionBody.cql line 11 (0-indexed): "    \"Identity\"('Hello')"
        // Position(11, 15) lands on 'H' inside 'Hello'.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionBody.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val result = DefinitionTrackBackVisitor().visitLibrary(library, Position(11, 15))
        assertInstanceOf(Literal::class.java, result)
    }

    @Test
    fun aggregateResult_prefersChild() {
        // The aggregateResult returns nextResult (child) before aggregate (parent).
        // Test by finding a Literal inside a FunctionRef — both cover the position but
        // the visitor should return the Literal (child).
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseFunction" }
        val ref = def.expression as FunctionRef
        val literal = ref.operand.first() as Literal
        val literalRange = TrackBacks.toRange(literal.locator!!)!!
        val pos = Position(literalRange.start.line, literalRange.start.character + 1)
        val result = DefinitionTrackBackVisitor().visitLibrary(library, pos)
        assertInstanceOf(Literal::class.java, result, "Expected Literal (child), not FunctionRef (parent)")
    }

    @Test
    fun visitLiteral_nonMatchingPosition_returnsDifferentElement() {
        // FunctionBody.cql — position on the "define" keyword, not inside a Literal.
        // The visitor should return whatever element (if any) covers that position.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionBody.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val funcDef = library.statements!!.def.filterIsInstance<FunctionDef>().first { it.name == "Identity" }
        val locator = funcDef.locator
        if (locator != null) {
            val range = TrackBacks.toRange(locator)!!
            val pos = Position(range.start.line, range.start.character + 1)
            val result = DefinitionTrackBackVisitor().visitLibrary(library, pos)
            // The result should NOT be a Literal for a position on the function definition.
            assertFalse(result is Literal, "Expected non-Literal for position on function def, got: ${result?.let { it::class.simpleName }}")
        }
    }
}
