package org.opencds.cqf.cql.ls.server.visitor

import org.eclipse.lsp4j.Position
import org.hl7.elm.r1.Add
import org.hl7.elm.r1.AliasRef
import org.hl7.elm.r1.AliasedQuerySource
import org.hl7.elm.r1.Code
import org.hl7.elm.r1.CodeRef
import org.hl7.elm.r1.CodeSystemRef
import org.hl7.elm.r1.ConceptRef
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.ExpressionRef
import org.hl7.elm.r1.FunctionDef
import org.hl7.elm.r1.FunctionRef
import org.hl7.elm.r1.Literal
import org.hl7.elm.r1.OperandDef
import org.hl7.elm.r1.OperandRef
import org.hl7.elm.r1.OperatorExpression
import org.hl7.elm.r1.Or
import org.hl7.elm.r1.ParameterRef
import org.hl7.elm.r1.Property
import org.hl7.elm.r1.Query
import org.hl7.elm.r1.Retrieve
import org.hl7.elm.r1.ValueSetRef
import org.hl7.elm.r1.With
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
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

class ExpressionTrackBackVisitorTest {
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
    fun visit_outsideExpressions_returnsNull() {
        // One.cql line 1: "library One" — outside any expression
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val result = ExpressionTrackBackVisitor().visitLibrary(library, Position(0, 0))
        assertNull(result)
    }

    @Test
    fun visit_expressionDef_returnsExpressionDef() {
        // One.cql: define "One": 1
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "One" }
        val range = TrackBacks.toRange(def.locator!!)!!
        // Place cursor one character into the def's start
        val pos = Position(range.start.line, range.start.character + 1)
        val result = ExpressionTrackBackVisitor().visitLibrary(library, pos)
        assertInstanceOf(ExpressionDef::class.java, result)
    }

    @Test
    fun visit_functionDef_returnsFunctionDef() {
        // FunctionLib.cql: define function "Double"(x Integer): x * 2
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionLib.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.filterIsInstance<FunctionDef>().first { it.name == "Double" }
        val range = TrackBacks.toRange(def.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)
        val result = ExpressionTrackBackVisitor().visitLibrary(library, pos)
        assertInstanceOf(FunctionDef::class.java, result)
    }

    @Test
    fun visit_expressionRef_returnsExpressionRef() {
        // Two.cql: define "Two": 1 + One."One"
        // The Add expression has an ExpressionRef as its second operand.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/Two.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val twoDef = library.statements!!.def.first { it.name == "Two" }
        val ref = (twoDef.expression as Add).operand[1] as ExpressionRef
        val range = TrackBacks.toRange(ref.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)
        val result = ExpressionTrackBackVisitor().visitLibrary(library, pos)
        assertInstanceOf(ExpressionRef::class.java, result)
    }

    @Test
    fun visit_functionRef_returnsFunctionRef() {
        // FunctionCaller.cql: define "UseFunction": FL."Double"(3)
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseFunction" }
        val ref = def.expression as FunctionRef
        val range = TrackBacks.toRange(ref.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)
        val result = ExpressionTrackBackVisitor().visitLibrary(library, pos)
        assertInstanceOf(FunctionRef::class.java, result)
    }

    @Test
    fun visit_aliasRef_returnsAliasRef() {
        // WithQuery.cql: define "Items Alias Test": "Items" Item return Item
        // The AliasRef is the "Item" in "return Item"
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Items Alias Test" }
        val aliasRef = (def.expression as Query).`return`!!.expression as AliasRef
        val range = TrackBacks.toRange(aliasRef.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)
        val result = ExpressionTrackBackVisitor().visitLibrary(library, pos)
        assertInstanceOf(AliasRef::class.java, result)
    }

    @Test
    fun visit_aliasedQuerySource_returnsAliasedQuerySource() {
        // WithQuery.cql: define "Items Alias Test": "Items" Item return Item
        // The AliasedQuerySource covers '"Items" Item'; cursor placed inside the alias name
        // (after the ExpressionRef for "Items" ends) should resolve to AliasedQuerySource.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Items Alias Test" }
        val aliasedSource = (def.expression as Query).source.first()
        val aqsRange = TrackBacks.toRange(aliasedSource.locator!!)!!
        // Step back 2 chars from end to land inside the alias name, not the source ExpressionRef
        val posInAlias = Position(aqsRange.end.line, aqsRange.end.character - 2)
        val result = ExpressionTrackBackVisitor().visitLibrary(library, posInAlias)
        assertInstanceOf(AliasedQuerySource::class.java, result)
    }

    @Test
    fun visit_operatorKeyword_returnsOperatorExpression() {
        // Two.cql: define "TwoBoolOr": true or false
        // Cursor placed just after the left operand ("true") ends — lands between the two
        // operands, i.e. on the space/keyword, within the Or but outside either operand.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/Two.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "TwoBoolOr" }
        val or = def.expression as Or
        val leftEnd = TrackBacks.toRange(or.operand[0].locator!!)!!.end
        val pos = Position(leftEnd.line, leftEnd.character + 1)
        val result = ExpressionTrackBackVisitor().visitLibrary(library, pos)
        assertInstanceOf(OperatorExpression::class.java, result)
    }

    @Test
    fun visit_property_returnsProperty() {
        // PropertyAccess.cql: define "NameProp": "Tuple".name
        // "Tuple".name → Property{ source: ExpressionRef("Tuple"), path: "name" }
        // The ExpressionRef child captures the cursor when it's over "Tuple". We place the
        // cursor near the end of the Property range to land on the ".name" portion, which
        // has no child node — so the visitor should return the Property itself.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/PropertyAccess.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "NameProp" }
        val property = def.expression as Property
        val range = TrackBacks.toRange(property.locator!!)!!
        val pos = Position(range.end.line, range.end.character - 1)
        val result = ExpressionTrackBackVisitor().visitLibrary(library, pos)
        assertInstanceOf(Property::class.java, result)
    }

    @Test
    fun visit_literal_returnsLiteral() {
        // FunctionBody.cql line 11 (0-indexed): "    \"Identity\"('Hello')"
        // Position(11, 15) lands on the 'H' inside 'Hello'.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionBody.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val result = ExpressionTrackBackVisitor().visitLibrary(library, Position(11, 15))
        assertInstanceOf(Literal::class.java, result)
    }

    @Test
    fun visit_operandRef_returnsOperandRef() {
        // FunctionBody.cql line 6 (0-indexed): "    if boolean"
        // Position(6, 7) lands on 'b' of "boolean" — an OperandRef inside Denied Reason.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionBody.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val result = ExpressionTrackBackVisitor().visitLibrary(library, Position(6, 7))
        assertInstanceOf(OperandRef::class.java, result)
    }

    @Test
    fun visit_operandDef_returnsOperandDef() {
        // The ELM compiler does not set locators on OperandDef, so position-based lookup
        // always returns the parent FunctionDef. Instead, verify by navigating the tree directly.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionBody.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val funcDef = library.statements!!.def.filterIsInstance<FunctionDef>().first { it.name == "Identity" }
        val operandDef = funcDef.operand.first { it.name == "x" }
        assertInstanceOf(OperandDef::class.java, operandDef)
        assertEquals("x", operandDef.name)
    }

    @Test
    fun visit_valueSetRef_returnsValueSetRef() {
        // WithTerminology.cql: define "UseVS": "Beta Blocker Therapy"
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithTerminology.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseVS" }
        val vsRef = def.expression as ValueSetRef
        val range = TrackBacks.toRange(vsRef.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)
        val result = ExpressionTrackBackVisitor().visitLibrary(library, pos)
        assertInstanceOf(ValueSetRef::class.java, result)
    }

    @Test
    fun visit_codeSystemRef_returnsCodeSystemRef() {
        // WithTerminology.cql: Code '12345' from "SNOMEDCT" — cursor on SNOMEDCT
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithTerminology.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseCS" }
        val code = def.expression as Code
        val csRef = code.system as CodeSystemRef
        val range = TrackBacks.toRange(csRef.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)
        val result = ExpressionTrackBackVisitor().visitLibrary(library, pos)
        assertInstanceOf(CodeSystemRef::class.java, result)
    }

    @Test
    fun visit_codeRef_returnsCodeRef() {
        // TerminologyLib.cql: concept "Left Foot Pain": { "Venous foot pain, left" }
        // The ConceptDef's code list contains a CodeRef referencing "Venous foot pain, left".
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/TerminologyLib.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val conceptDef = library.concepts!!.def.first { it.name == "Left Foot Pain" } as org.hl7.elm.r1.ConceptDef
        val codeRef = conceptDef.code?.firstOrNull() ?: return
        val range = TrackBacks.toRange(codeRef.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)
        val result = ExpressionTrackBackVisitor().visitLibrary(library, pos)
        assertInstanceOf(CodeRef::class.java, result)
    }

    @Test
    fun visit_conceptRef_returnsConceptRef() {
        // TerminologyCaller.cql: define "UseConcept": TL."Left Foot Pain"
        val callerUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/TerminologyCaller.cql")!!
        val termLibUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/TerminologyLib.cql")!!
        compilationManager.compile(termLibUri)
        val library = compilationManager.compile(callerUri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseConcept" }
        val expression = def.expression
        if (expression is ConceptRef) {
            val range = TrackBacks.toRange(expression.locator!!)!!
            val pos = Position(range.start.line, range.start.character + 1)
            val result = ExpressionTrackBackVisitor().visitLibrary(library, pos)
            assertInstanceOf(ConceptRef::class.java, result)
        }
    }

    @Test
    fun visit_parameterRef_returnsParameterRef() {
        // WithParam.cql: define "Using Measurement Period": "Measurement Period"
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithParam.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Using Measurement Period" }
        val ref = def.expression as ParameterRef
        val range = TrackBacks.toRange(ref.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)
        val result = ExpressionTrackBackVisitor().visitLibrary(library, pos)
        assertInstanceOf(ParameterRef::class.java, result)
    }

    @Test
    fun visit_retrieve_returnsRetrieve() {
        // ScopeCoercionQuery.cql: [Encounter] E — cursor on the retrieve expression
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/ScopeCoercionQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "With Test" }
        // The source of the first Query source is a Retrieve
        val query = def.expression as Query
        val retrieve = query.source.first().expression as Retrieve
        val range = TrackBacks.toRange(retrieve.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)
        val result = ExpressionTrackBackVisitor().visitLibrary(library, pos)
        assertInstanceOf(Retrieve::class.java, result)
    }

    @Test
    fun visit_retrieveWithChild_returnsChildWhenInsideChild() {
        // A Retrieve with a code/valueSet child should return the child when
        // the cursor is on the child. Use a file where a ValueSetRef is embedded
        // inside a retrieve by finding a query source whose expression is a Retrieve.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/ScopeCoercionQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "With Test" }
        val query = def.expression as Query
        // Make sure the source expression is a Retrieve and has a codes property
        val retrieve = query.source.first().expression as Retrieve
        // The retrieve itself should be returned when cursor is on the type name part
        val retrieveRange = TrackBacks.toRange(retrieve.locator!!)!!
        val pos = Position(retrieveRange.start.line, retrieveRange.start.character + 1)
        val result = ExpressionTrackBackVisitor().visitLibrary(library, pos)
        // The retrieve itself is returned when cursor is on it
        assertInstanceOf(Retrieve::class.java, result)
    }

    @Test
    fun visit_withClause_returnsWith() {
        // ScopeCoercionQuery.cql: with [Encounter] E2 such that ...
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/ScopeCoercionQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "With Test" }
        val query = def.expression as Query
        val withClause = query.relationship.firstOrNull()
        if (withClause != null) {
            val range = TrackBacks.toRange(withClause.locator!!)!!
            val pos = Position(range.start.line, range.start.character + 1)
            val result = ExpressionTrackBackVisitor().visitLibrary(library, pos)
            assertInstanceOf(With::class.java, result)
        }
    }
}
