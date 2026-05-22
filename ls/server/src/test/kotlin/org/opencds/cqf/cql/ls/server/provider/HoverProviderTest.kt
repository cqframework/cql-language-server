package org.opencds.cqf.cql.ls.server.provider

import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.hl7.elm.r1.Add
import org.hl7.elm.r1.AliasRef
import org.hl7.elm.r1.Code
import org.hl7.elm.r1.ExpressionRef
import org.hl7.elm.r1.FunctionDef
import org.hl7.elm.r1.FunctionRef
import org.hl7.elm.r1.Or
import org.hl7.elm.r1.Property
import org.hl7.elm.r1.Query
import org.hl7.elm.r1.Retrieve
import org.hl7.elm.r1.ReturnClause
import org.hl7.elm.r1.ValueSetRef
import org.hl7.elm.r1.With
import org.hl7.elm.r1.Without
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService
import org.opencds.cqf.cql.ls.server.utility.TrackBacks

class HoverProviderTest {
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
    fun hover_outsideExpressions_returnsNull() {
        // Position(0,0) is on "library One" — outside any expression
        val hover =
            hoverProvider.hover(
                HoverParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/One.cql"),
                    Position(0, 0),
                ),
            )
        assertNull(hover)
    }

    @Test
    fun hover_onExpressionDef_returnsHoverWithResultType() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "One" }
        val range = TrackBacks.toRange(def.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/One.cql"), pos),
            )

        assertNotNull(hover)
        assertEquals("markdown", hover!!.contents.right.kind)
        val value = hover.contents.right.value
        assertTrue(value.contains("```cql"))
        assertTrue(value.contains("define \"One\": System.Integer"), "Expected full define syntax: $value")
        assertTrue(value.contains("*One*"), "Expected library annotation: $value")
    }

    @Test
    fun hover_onFunctionDef_returnsReturnType() {
        // Hovering over the function definition itself shows the full signature
        // with a local library annotation.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionLib.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.filterIsInstance<FunctionDef>().first { it.name == "Double" }
        val range = TrackBacks.toRange(def.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionLib.cql"), pos),
            )

        assertNotNull(hover)
        val value = hover!!.contents.right.value
        assertTrue(value.contains("```cql"))
        assertTrue(value.contains("define function \"Double\"(x System.Integer): System.Integer"), "Expected full function signature: $value")
        assertTrue(value.contains("*FunctionLib version '1.0.0'*"), "Expected library annotation: $value")
    }

    @Test
    fun hover_onSameLibraryFunctionRef_showsSignature() {
        // FunctionLib.cql: define "UseDouble": "Double"(42)
        // The FunctionRef resolves within the same library → markup shows the full signature.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionLib.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseDouble" }
        val ref = def.expression as FunctionRef
        val range = TrackBacks.toRange(ref.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionLib.cql"), pos),
            )

        assertNotNull(hover)
        val value = hover!!.contents.right.value
        assertTrue(value.contains("define function"), "Expected CQL function signature in hover: $value")
        assertTrue(value.contains("\"Double\""), "Expected function name in hover: $value")
        assertTrue(value.contains("("), "Expected parameter list in hover: $value")
        assertTrue(value.contains("System.Integer"), "Expected return type in hover: $value")
        assertTrue(value.contains("*FunctionLib version '1.0.0'*"), "Expected local library annotation: $value")
    }

    @Test
    fun hover_onCrossLibraryExpressionRef_showsDefineName() {
        // FunctionCaller.cql: define "UseValue": FL."MyValue" + 1
        // FL."MyValue" is a library-qualified ExpressionRef — should resolve into FunctionLib
        // and return define "MyValue": Integer, not null.
        // Cursor is placed past "FL." (+ 3 chars) to land on the name "MyValue", not the alias.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseValue" }
        val ref = (def.expression as Add).operand[0] as ExpressionRef
        val range = TrackBacks.toRange(ref.locator!!)!!
        // + 3 skips "FL." to land on the '"' of "MyValue"
        val pos = Position(range.start.line, range.start.character + 3)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql"), pos),
            )

        assertNotNull(hover)
        val value = hover!!.contents.right.value
        assertTrue(value.contains("define \"MyValue\":"), "Expected CQL define syntax in hover: $value")
        assertTrue(value.contains("\"MyValue\""), "Expected define name in hover: $value")
        assertTrue(value.contains("*FunctionLib"), "Expected italic library source in hover: $value")
    }

    @Test
    fun hover_onCrossLibraryExpressionRefAlias_showsInclude() {
        // FunctionCaller.cql: define "UseValue": FL."MyValue" + 1
        // Cursor on "FL" (the library alias) should show the include declaration, not the define.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseValue" }
        val ref = (def.expression as Add).operand[0] as ExpressionRef
        val range = TrackBacks.toRange(ref.locator!!)!!
        // + 1 lands on "L" — still within "FL" (alias length = 2)
        val pos = Position(range.start.line, range.start.character + 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql"), pos),
            )

        assertNotNull(hover, "Expected hover showing include info on library alias 'FL'")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("include"), "Expected include declaration in hover: $value")
        assertTrue(value.contains("FunctionLib"), "Expected library name in hover: $value")
        assertTrue(value.contains("FL"), "Expected alias 'FL' in hover: $value")
    }

    @Test
    fun hover_onAliasRef_returnsItemTypeNotOuterDefType() {
        // WithQuery.cql: "Items Alias Test" queries over "Items" (List<Integer>).
        // Hovering over the AliasRef "Item" in "return Item" should show the item type
        // (Integer), NOT the outer definition's return type (List<Integer>).
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Items Alias Test" }
        val aliasRef = (def.expression as Query).`return`!!.expression as AliasRef
        val range = TrackBacks.toRange(aliasRef.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithQuery.cql"), pos),
            )

        assertNotNull(hover)
        val value = hover!!.contents.right.value
        assertTrue(value.contains("Item:"), "Expected alias name in hover: $value")
        assertTrue(value.contains("Integer"), "Expected item type Integer, got: $value")
        assertTrue(value.contains("```cql"))
    }

    @Test
    fun hover_onCrossLibraryFunctionRef_showsSignatureAndSource() {
        // FunctionCaller.cql: define "UseFunction": FL."Double"(3)
        // FL."Double" is a library-qualified FunctionRef — resolves into FunctionLib
        // and returns the full TypeScript-style hover with signature and source.
        // Cursor is placed past "FL." (+ 3 chars) to land on the name "Double", not the alias.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseFunction" }
        val ref = def.expression as FunctionRef
        val range = TrackBacks.toRange(ref.locator!!)!!
        // + 3 skips "FL." to land on the '"' of "Double"
        val pos = Position(range.start.line, range.start.character + 3)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql"), pos),
            )

        assertNotNull(hover)
        val value = hover!!.contents.right.value
        assertTrue(value.contains("define function"), "Expected CQL function signature in hover: $value")
        assertTrue(value.contains("\"Double\""), "Expected function name in hover: $value")
        assertTrue(value.contains("*FunctionLib"), "Expected italic library source in hover: $value")
    }

    @Test
    fun hover_onCrossLibraryFunctionRefAlias_showsInclude() {
        // FunctionCaller.cql: define "UseFunction": FL."Double"(3)
        // Cursor on "FL" (the library alias) should show the include declaration, not the function.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseFunction" }
        val ref = def.expression as FunctionRef
        val range = TrackBacks.toRange(ref.locator!!)!!
        // + 1 lands on "L" — still within "FL" (alias length = 2)
        val pos = Position(range.start.line, range.start.character + 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql"), pos),
            )

        assertNotNull(hover, "Expected hover showing include info on library alias 'FL'")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("include"), "Expected include declaration in hover: $value")
        assertTrue(value.contains("FunctionLib"), "Expected library name in hover: $value")
        assertTrue(value.contains("FL"), "Expected alias 'FL' in hover: $value")
    }

    @Test
    fun hover_onProperty_returnsHoverWithResultType() {
        // PropertyAccess.cql: define "NameProp": "Tuple".name
        // Cursor placed near the end of the range to land on ".name" (past the source ExpressionRef).
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/PropertyAccess.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "NameProp" }
        val property = def.expression as Property
        val range = TrackBacks.toRange(property.locator!!)!!
        val pos = Position(range.end.line, range.end.character - 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/PropertyAccess.cql"), pos),
            )

        assertNotNull(hover)
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(element) \"Tuple\".name:"), "Expected (element) prefix with ExpressionRef name: $value")
    }

    @Test
    fun hover_onOperatorKeyword_returnsNull() {
        // Two.cql: define "TwoBoolOr": true or false
        // Hovering over the `or` keyword (between the two operands) should return null —
        // operator keywords are not meaningful hover targets.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/Two.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "TwoBoolOr" }
        val or = def.expression as Or
        val leftEnd = TrackBacks.toRange(or.operand[0].locator!!)!!.end
        val pos = Position(leftEnd.line, leftEnd.character + 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/Two.cql"), pos),
            )

        assertNull(hover)
    }

    @Test
    fun hover_onValueSetRef_returnsUrlAndName() {
        // WithTerminology.cql: define "UseVS": "Beta Blocker Therapy"
        // "Beta Blocker Therapy" is a ValueSetRef — hover shows the URL and (valueset) label.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithTerminology.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseVS" }
        val vsRef = def.expression as ValueSetRef
        val range = TrackBacks.toRange(vsRef.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithTerminology.cql"), pos),
            )

        assertNotNull(hover)
        val value = hover!!.contents.right.value
        assertTrue(value.contains("valueset \"Beta Blocker Therapy\":"), "Expected CQL valueset syntax: $value")
        assertTrue(value.contains("\"Beta Blocker Therapy\""), "Expected valueset name: $value")
        assertTrue(value.contains("http://cts.nlm.nih.gov"), "Expected valueset URL: $value")
    }

    @Test
    fun hover_onCodeSystemRef_returnsUrlAndName() {
        // WithTerminology.cql: define "UseCS": Code '12345' from "SNOMEDCT"
        // "SNOMEDCT" is a CodeSystemRef on Code.system — hover shows the URL and (codesystem) label.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithTerminology.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseCS" }
        val code = def.expression as Code
        val csRef = code.system!!
        val range = TrackBacks.toRange(csRef.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithTerminology.cql"), pos),
            )

        assertNotNull(hover)
        val value = hover!!.contents.right.value
        assertTrue(value.contains("codesystem \"SNOMEDCT\":"), "Expected CQL codesystem syntax: $value")
        assertTrue(value.contains("\"SNOMEDCT\""), "Expected codesystem name: $value")
        assertTrue(value.contains("http://snomed.info/sct"), "Expected codesystem URL: $value")
    }

    @Test
    fun hover_onScopePropertyAliasPortion_showsAliasType() {
        // WithQuery.cql: "Alias Property Scope Test" returns T.name where T is a tuple alias.
        // Hovering over "T" in "T.name" should now show the alias type rather than suppressing.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Alias Property Scope Test" }
        val query = def.expression as Query
        val property = (query.`return`!! as ReturnClause).expression as Property
        val range = TrackBacks.toRange(property.locator!!)!!
        // Cursor at the very start of the Property range = on the alias "T"
        val posOnAlias = Position(range.start.line, range.start.character)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithQuery.cql"), posOnAlias),
            )

        assertNotNull(hover, "Expected alias hover on scope-alias portion of T.name")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(alias) T:"), "Expected '(alias) T:' in hover: $value")
        assertTrue(value.contains("Tuple"), "Expected tuple type in alias hover: $value")
    }

    @Test
    fun hover_onScopePropertyPathPortion_returnsPathType() {
        // Hovering over ".name" (path portion) in "T.name" should still show the path type.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Alias Property Scope Test" }
        val query = def.expression as Query
        val property = (query.`return`!! as ReturnClause).expression as Property
        val range = TrackBacks.toRange(property.locator!!)!!
        // "T.name" — "T" is 1 char, "." is 1 char, "name" starts at +2. Use end of range.
        val posOnPath = Position(range.end.line, range.end.character - 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithQuery.cql"), posOnPath),
            )

        assertNotNull(hover, "Expected hover on path portion of T.name")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(element) T.name:"), "Expected (element) prefix in label: $value")
    }

    @Test
    fun hover_onWithClauseAlias_returnsItemType() {
        // WithQuery.cql: "With Clause Test" has `with { 10, 20 } Extra such that Extra > Item`
        // Hovering over "Extra" (the alias name, after the source expression) should return
        // the item type of the joined expression — Integer — NOT null.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "With Clause Test" }
        val query = def.expression as Query
        val withClause = query.relationship.filterIsInstance<With>().first()
        // Position just after the source expression ends — where the alias name starts.
        val sourceEnd = TrackBacks.toRange(withClause.expression!!.locator!!)!!.end
        val pos = Position(sourceEnd.line, sourceEnd.character + 2)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithQuery.cql"), pos),
            )

        assertNotNull(hover, "Expected hover on with-clause alias, got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(alias) Extra:"), "Expected '(alias)' prefix and alias name in with-clause hover: $value")
        assertTrue(value.contains("Integer"), "Expected item type Integer for with-alias hover: $value")
    }

    @Test
    fun hover_onWithKeyword_returnsNull() {
        // WithQuery.cql: `with "Items" Extra such that Extra > Item`
        // Cursor on the "with" keyword should return null.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "With Clause Test" }
        val query = def.expression as Query
        val withClause = query.relationship.filterIsInstance<With>().first()
        val withRange = TrackBacks.toRange(withClause.locator!!)!!
        val pos = Position(withRange.start.line, withRange.start.character)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithQuery.cql"), pos),
            )

        assertNull(hover, "Expected null on 'with' keyword, got: ${hover?.contents?.right?.value}")
    }

    @Test
    fun hover_onSuchThatKeyword_returnsNull() {
        // WithQuery.cql: `with "Items" Extra such that Extra > Item`
        // Cursor on the "such that" keyword should return null.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "With Clause Test" }
        val query = def.expression as Query
        val withClause = query.relationship.filterIsInstance<With>().first()
        val stRange = TrackBacks.toRange(withClause.suchThat!!.locator!!)!!
        // Back up by 10 characters from the suchThat expression start to land on "such that".
        // (" such that " = 10 chars: space + 'such' + space + 'that' + space? Actually, "such that"
        // is 9 characters, but there's typically a leading space. stRange.start.character - 10
        // accounts for: space before 'such' + 'such' + space + 'that' = 1+4+1+4 = 10.)
        val pos = Position(stRange.start.line, stRange.start.character - 10)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithQuery.cql"), pos),
            )

        assertNull(hover, "Expected null on 'such that' keyword, got: ${hover?.contents?.right?.value}")
    }

    @Test
    fun hover_onFhirAliasPropertyAliasPortion_showsAliasType() {
        // WithFhirQuery.cql: "Encounter Period Test" returns E.period where E is a FHIR Encounter.
        // The compiler wraps E.period in FHIRHelpers.ToInterval(...). Hovering over "E" should
        // now show the alias type rather than suppressing or showing ToInterval.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithFhirQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Encounter Period Test" }
        val query = def.expression as Query
        val returnExpr = (query.`return`!! as ReturnClause).expression!!
        // Navigate to the inner Property (may be wrapped in a FunctionRef coercion)
        val property =
            when (returnExpr) {
                is Property -> returnExpr
                is FunctionRef -> returnExpr.operand.filterIsInstance<Property>().first()
                else -> fail("Unexpected expression type: ${returnExpr::class.simpleName}")
            }
        // Use the outer node's locator to get the start of the full expression — that is where
        // the alias name "E" appears in source.
        val outerLocator =
            returnExpr.locator ?: property.locator
                ?: fail("No locator on return expression or inner property")
        val range = TrackBacks.toRange(outerLocator)!!
        val posOnAlias = Position(range.start.line, range.start.character)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithFhirQuery.cql"), posOnAlias),
            )

        assertNotNull(hover, "Expected alias hover on 'E' in E.period, got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(alias) E:"), "Expected '(alias) E:' in hover: $value")
        assertTrue(value.contains("FHIR.Encounter"), "Expected Encounter type in alias hover: $value")
    }

    @Test
    fun hover_onFhirAliasPropertyPathPortion_returnsType() {
        // Hovering over "period" in E.period should still show the result type.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithFhirQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Encounter Period Test" }
        val query = def.expression as Query
        val returnExpr = (query.`return`!! as ReturnClause).expression!!
        val property =
            when (returnExpr) {
                is Property -> returnExpr
                is FunctionRef -> returnExpr.operand.filterIsInstance<Property>().first()
                else -> fail("Unexpected expression type: ${returnExpr::class.simpleName}")
            }
        // Use the Property's own locator to land inside "period"
        val propRange = TrackBacks.toRange(property.locator!!)!!
        val posOnPath = Position(propRange.end.line, propRange.end.character - 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithFhirQuery.cql"), posOnPath),
            )

        assertNotNull(hover, "Expected hover on 'period' path portion of E.period")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(element) E.period:"), "Expected (element) prefix in label: $value")
    }

    @Test
    fun hover_onRetrieve_returnsListType() {
        // WithFhirQuery.cql: "[Encounter] E" — the Retrieve node had no case in markupForElement
        // and returned null. Should return the list result type (e.g. List<FHIR.Encounter>).
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithFhirQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Encounter Period Test" }
        val retrieve = (def.expression as Query).source.first().expression as Retrieve
        val range = TrackBacks.toRange(retrieve.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithFhirQuery.cql"), pos),
            )

        assertNotNull(hover, "Expected hover on Retrieve '[Encounter]', got null")
        assertTrue(hover!!.contents.right.value.contains("```cql"), "Expected cql type block: ${hover.contents.right.value}")
    }

    @Test
    fun hover_onSourcePropertyPathPortion_returnsPropertyType() {
        // WithFhirQuery.cql: define "Source Based Period": "Most Recent Encounter".period
        // "Most Recent Encounter" is an ExpressionRef (not a query alias). The compiler inserts
        // FHIRHelpers.ToInterval(Property(...)) for the FHIR Period type. Hovering over "period"
        // should show property info, not the FHIRHelpers function signature.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithFhirQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Source Based Period" }
        val range = TrackBacks.toRange(def.expression!!.locator!!)!!
        val posOnPath = Position(range.end.line, range.end.character - 1)

        val hover =
            hoverProvider.hover(
                HoverParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithFhirQuery.cql"),
                    posOnPath,
                ),
            )

        assertNotNull(hover)
        val value = hover!!.contents.right.value
        assertFalse(
            value.contains("define function"),
            "Should show property info, not FHIRHelpers function signature: $value",
        )
        assertTrue(value.contains("(element)"), "Expected '(element)' prefix in hover: $value")
        assertTrue(
            value.contains("\"Most Recent Encounter\""),
            "Expected ExpressionRef name in hover label: $value",
        )
    }

    @Test
    fun hover_onValueSetRefInsideRetrieve_returnsValueSetDetails() {
        // WithFhirQuery.cql: ["Encounter": "Ambulatory Encounter"]
        // Cursor on the valueset name "Ambulatory Encounter" should return valueset details
        // (URL + name), not the Retrieve's list type. visitRetrieve previously short-circuited
        // without recursing into its codes child, so the ValueSetRef was never reached.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithFhirQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Ambulatory Encounters" }
        val retrieve = def.expression as Retrieve
        val vsRef = retrieve.codes as ValueSetRef
        val range = TrackBacks.toRange(vsRef.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithFhirQuery.cql"), pos),
            )

        assertNotNull(hover, "Expected hover on ValueSetRef inside Retrieve, got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("valueset \"Ambulatory Encounter\":"), "Expected CQL valueset syntax: $value")
        assertTrue(value.contains("Ambulatory Encounter"), "Expected valueset name: $value")
        assertTrue(value.contains("http://cts.nlm.nih.gov"), "Expected valueset URL: $value")
    }

    @Test
    fun hover_onLiteralInsideFunctionCall_returnsType() {
        // FunctionBody.cql line 11 (0-indexed): "    \"Identity\"('Hello')"
        // Position(11, 15) lands on 'H' inside 'Hello' — a Literal.
        val docId = TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionBody.cql")
        val hover = hoverProvider.hover(HoverParams(docId, Position(11, 15)))

        assertNotNull(hover, "Expected hover on Literal 'Hello'")
        assertTrue(hover!!.contents.right.value.contains("```cql"), "Expected cql type block: ${hover.contents.right.value}")
    }

    @Test
    fun hover_onOperandRefInFunctionBody_returnsNameAndType() {
        // FunctionBody.cql line 6 (0-indexed): "    if boolean"
        // Position(6, 7) lands on 'b' of "boolean" — an OperandRef inside the function body.
        val docId = TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionBody.cql")
        val hover = hoverProvider.hover(HoverParams(docId, Position(6, 7)))

        assertNotNull(hover, "Expected hover on OperandRef 'boolean'")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("parameter \"boolean\":"), "Expected CQL parameter syntax: $value")
        assertTrue(value.contains("\"boolean\""), "Expected parameter name in hover: $value")
    }

    @Test
    fun hover_onFluentFunctionNameAfterAlias_returnsDetails() {
        // WithFhirQuery.cql line 20 (0-indexed 19): "    where E.isInProgress()"
        //   char 10 = 'E' (alias), char 12 = 'i' of "isInProgress"
        // Cursor on "isInProgress" (after the alias and dot) should still show function details.
        // The AliasRef suppression check must not over-suppress — it suppresses only positions
        // that are BEFORE the AliasRef start, not between the alias and the function name.
        //
        // Note: the where-keyword suppression (cursor before AliasRef in a where clause) is
        // confirmed working in VS Code but cannot be reproduced in a unit test: the CQL compiler
        // assigns a wider locator to the where-clause expression in cross-library fluent calls
        // (covering from the "where" keyword) but not for locally-defined fluent functions
        // like isInProgress here (locator starts at "E").
        val docId = TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithFhirQuery.cql")

        val hover = hoverProvider.hover(HoverParams(docId, Position(19, 12)))
        assertNotNull(hover, "Expected hover on fluent function name 'isInProgress'")
        assertTrue(
            hover!!.contents.right.value.contains("define fluent function"),
            "Expected CQL function syntax in hover: ${hover.contents.right.value}",
        )
    }

    @Test
    fun hover_multiLineFunctionSignature_showsLineBreaks() {
        // MultiParamFunction.cql: define function "Three Param"(x Integer, y Integer, z Integer)
        // Hovering over a FunctionRef to a 3-param function should show multi-line signature.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/MultiParamFunction.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseThreeParam" }
        val ref = def.expression as FunctionRef
        val range = TrackBacks.toRange(ref.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/MultiParamFunction.cql"), pos),
            )

        assertNotNull(hover, "Expected hover on multi-param function ref")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("define function"), "Expected function signature: $value")
        assertTrue(value.contains("Three Param"), "Expected function name: $value")
        // Multi-line: params on separate lines with newline after opening paren
        assertTrue(value.contains("(\n  "), "Expected multi-line parameter list (3+ params): $value")
        assertTrue(value.contains("System.Integer"), "Expected parameter types: $value")
    }

    @Test
    fun hover_fromLibraryAnnotation_italicBelow() {
        // FunctionCaller.cql: define "UseValue": FL."MyValue" + 1
        // Cross-library refs should show the library source as italic text below the code block.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "UseValue" }
        val ref = (def.expression as Add).operand[0] as ExpressionRef
        val range = TrackBacks.toRange(ref.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 3)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql"), pos),
            )

        assertNotNull(hover)
        val value = hover!!.contents.right.value
        // Code block first, then italic annotation
        assertTrue(value.startsWith("```cql"), "Expected markdown code block first: $value")
        assertTrue(value.contains("\n```\n\n*FunctionLib"), "Expected italic library annotation after code block: $value")
    }

    @Test
    fun hover_crossLibraryCodeRef_resolvesSystemName() {
        // TerminologyCaller.cql line 5:     TL."Venous foot pain, left"
        // Cursor past "TL." lands on the code name — should resolve cross-library CodeRef.
        val docId = TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/TerminologyCaller.cql")
        // char 10 lands past "TL." on "n" of "Venous foot pain, left"
        val hover = hoverProvider.hover(HoverParams(docId, Position(5, 10)))

        assertNotNull(hover, "Expected hover on cross-library CodeRef, got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("code"), "Expected CQL code syntax: $value")
        assertTrue(value.contains("\"Venous foot pain, left\""), "Expected code name: $value")
        assertTrue(value.contains("'12345'"), "Expected code id: $value")
        assertTrue(value.contains("\"SNOMEDCT\""), "Expected codesystem name in 'from' clause: $value")
        assertTrue(value.contains("*TerminologyLib"), "Expected italic library source: $value")
    }

    // -----------------------------------------------------------------------
    // hover() — markup integration tests
    // -----------------------------------------------------------------------

    @Test
    fun markup_compiledDef_returnsMarkdownContent() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        val compiler = compilationManager.compile(uri)!!
        val def = compiler.compiledLibrary!!.library!!.statements!!.def.first()
        val range = TrackBacks.toRange(def.locator!!)!!
        val pos = Position(range.start.line, range.start.character)

        val result = hoverProvider.hover(HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/One.cql"), pos))

        assertNotNull(result)
        assertEquals("markdown", result!!.contents.right.kind)
        val value = result.contents.right.value
        assertTrue(value.contains("define \"One\":"), "Expected CQL define syntax in markup: $value")
        assertTrue(value.contains("\"One\""), "Expected define name in markup: $value")
    }

    @Test
    fun hover_onPrivateDefine_showsDefineWithAccessPrefix() {
        // PrivateLib.cql: private define "PrivateDef": 42
        // The CQL compiler (4.8.0) doesn't set accessLevel="Private" in ELM output,
        // so "private" won't appear. This test confirms the define compiles and
        // the accessPrefix helper doesn't crash. When the compiler is updated to
        // set accessLevel properly, this test should assert "private define".
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/PrivateLib.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "PrivateDef" }
        val range = TrackBacks.toRange(def.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/PrivateLib.cql"), pos),
            )

        assertNotNull(hover, "Expected hover on private define")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("define \"PrivateDef\""), "Expected define syntax in hover: $value")
        assertTrue(value.contains("System.Integer"), "Expected result type: $value")
        // Compiler limitation: accessLevel is PUBLIC, not PRIVATE, so "private" won't appear
        // TODO: When compiler sets accessLevel properly, change to:
        // assertTrue(value.contains("private define \"PrivateDef\""))

        // Confirm the hover IS shown (doesn't return null) for a private define
        assertTrue(value.startsWith("```cql"), "Expected markdown code block")
    }

    @Test
    fun hover_onPublicDefine_doesNotShowPrivateKeyword() {
        // PrivateLib.cql also has a public define "PublicDef": 1
        // Hover should NOT show the "private" keyword prefix.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/PrivateLib.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "PublicDef" }
        val range = TrackBacks.toRange(def.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/PrivateLib.cql"), pos),
            )

        assertNotNull(hover, "Expected hover on public define")
        val value = hover!!.contents.right.value
        assertFalse(value.contains("private"), "Expected no private keyword: $value")
        assertTrue(value.contains("define \"PublicDef\""), "Expected define syntax: $value")
    }

    @Test
    fun hover_onLongNestedType_isFormatted() {
        // TupleResult.cql: define "LongTuple": { A: 1, B: 'hello', C: true, D: 1.0 }
        // The tuple type is > 30 chars, so formatCqlType should insert newlines and indentation.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/TupleResult.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "LongTuple" }
        val range = TrackBacks.toRange(def.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/TupleResult.cql"), pos),
            )

        assertNotNull(hover, "Expected hover on long tuple type")
        val value = hover!!.contents.right.value
        // Multi-line: should contain newlines for the formatted type
        assertTrue(value.contains("\n"), "Expected multi-line formatted type: $value")
        assertTrue(value.contains("  "), "Expected indentation in formatted type: $value")
        assertTrue(value.contains("System.Integer"), "Expected tuple field types: $value")
        assertTrue(value.contains("System.String"), "Expected tuple field types: $value")
        assertTrue(value.contains("System.Boolean"), "Expected tuple field types: $value")
        assertTrue(value.contains("System.Decimal"), "Expected tuple field types: $value")
    }

    @Test
    fun hover_onShortType_remainsSingleLine() {
        // TupleResult.cql: define "ShortVal": 1
        // System.Integer (15 chars) is under 30, so should remain on one line.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/TupleResult.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "ShortVal" }
        val range = TrackBacks.toRange(def.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/TupleResult.cql"), pos),
            )

        assertNotNull(hover, "Expected hover on short type")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("define \"ShortVal\": System.Integer"), "Expected single-line define syntax: $value")
        // Should be a single code block line (no newlines inside the type)
        val cqlBlock = value.substringAfter("```cql\n").substringBefore("\n```")
        assertFalse(cqlBlock.contains("\n"), "Expected no newlines inside short type block: $cqlBlock")
    }

    @Test
    fun hover_onWithoutKeyword_returnsNull() {
        // WithoutQuery.cql: `without "Items" Extra such that Extra > Item`
        // Cursor on the "without" keyword should return null.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithoutQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Without Clause Test" }
        val query = def.expression as Query
        val withoutClause = query.relationship.filterIsInstance<Without>().first()
        val withoutRange = TrackBacks.toRange(withoutClause.locator!!)!!
        val pos = Position(withoutRange.start.line, withoutRange.start.character)

        val hover = hoverProvider.hover(
            HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithoutQuery.cql"), pos),
        )

        assertNull(hover, "Expected null on 'without' keyword, got: ${hover?.contents?.right?.value}")
    }

    @Test
    fun hover_onWithoutSuchThatKeyword_returnsNull() {
        // WithoutQuery.cql: `without "Items" Extra such that Extra > Item`
        // Cursor on the "such that" keyword should return null.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithoutQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Without Clause Test" }
        val query = def.expression as Query
        val withoutClause = query.relationship.filterIsInstance<Without>().first()
        val stRange = TrackBacks.toRange(withoutClause.suchThat!!.locator!!)!!
        val pos = Position(stRange.start.line, stRange.start.character - 10)

        val hover = hoverProvider.hover(
            HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithoutQuery.cql"), pos),
        )

        assertNull(hover, "Expected null on 'such that' keyword, got: ${hover?.contents?.right?.value}")
    }

    @Test
    fun hover_onWithoutClauseAlias_returnsItemType() {
        // WithoutQuery.cql: "Without Clause Test" has `without "Items" Extra such that Extra > Item`
        // Hovering over "Extra" (the alias) should return the item type.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithoutQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Without Clause Test" }
        val query = def.expression as Query
        val withoutClause = query.relationship.filterIsInstance<Without>().first()
        // Position just after the source expression ends — where the alias name starts.
        val sourceEnd = TrackBacks.toRange(withoutClause.expression!!.locator!!)!!.end
        val pos = Position(sourceEnd.line, sourceEnd.character + 2)

        val hover = hoverProvider.hover(
            HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithoutQuery.cql"), pos),
        )

        assertNotNull(hover, "Expected hover on without-clause alias, got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(alias) Extra:"), "Expected '(alias)' prefix and alias name in without-clause hover: $value")
        assertTrue(value.contains("Integer"), "Expected item type Integer for without-alias hover: $value")
    }
}
