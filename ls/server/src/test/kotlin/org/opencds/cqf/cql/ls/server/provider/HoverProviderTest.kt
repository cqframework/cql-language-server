package org.opencds.cqf.cql.ls.server.provider

import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.hl7.elm.r1.Add
import org.hl7.elm.r1.AliasRef
import org.hl7.elm.r1.And
import org.hl7.elm.r1.Code
import org.hl7.elm.r1.ExpressionRef
import org.hl7.elm.r1.FunctionDef
import org.hl7.elm.r1.FunctionRef
import org.hl7.elm.r1.In
import org.hl7.elm.r1.Or
import org.hl7.elm.r1.Property
import org.hl7.elm.r1.Query
import org.hl7.elm.r1.Retrieve
import org.hl7.elm.r1.ReturnClause
import org.hl7.elm.r1.UnaryExpression
import org.hl7.elm.r1.ValueSetRef
import org.hl7.elm.r1.With
import org.hl7.elm.r1.Without
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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
import org.opencds.cqf.cql.ls.server.visitor.CqlParseTreeVisitor

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
        assertTrue(value.contains("define function Double(x System.Integer): System.Integer"), "Expected full function signature: $value")
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
        assertTrue(value.contains("function Double"), "Expected function name in hover: $value")
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
        assertTrue(value.contains("function Double"), "Expected function name in hover: $value")
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
        assertTrue(value.contains("FHIR.Encounter"), "Expected FHIR-qualified Encounter type in alias hover: $value")
    }

    @Test
    fun hover_onWithClauseSourceAliasPropertyAliasPortion_showsAliasType() {
        // WithClauseScopeTest.cql: "With Scope Test" uses a with-clause with
        // `such that AdultDepressionScreening.status is not null`.
        // Hovering over "AdultDepressionScreening" (source alias, not with-clause alias)
        // in the suchThat property access should show the alias type.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithClauseScopeTest.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "With Scope Test" }
        val query = def.expression as Query
        val with = query.relationship.first() as With
        // The suchThat is Not(IsNull(Property(scope=AdultDepressionScreening, path=status)))
        // Navigate through UnaryExpression wrappers to find the Property
        var current = with.suchThat
        while (current is UnaryExpression) {
            current = current.operand
        }
        val property =
            current as? Property
                ?: fail("Expected Property as deepest suchThat operand, got: ${current?.let { it::class.simpleName } ?: "null"}")
        val range = TrackBacks.toRange(property.locator!!)!!
        val posOnAlias = Position(range.start.line, range.start.character)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithClauseScopeTest.cql"), posOnAlias),
            )

        assertNotNull(hover, "Expected alias hover on 'AdultDepressionScreening' in suchThat property access, got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(alias) AdultDepressionScreening:"), "Expected '(alias) AdultDepressionScreening:' in hover: $value")
        assertTrue(value.contains("FHIR.Encounter"), "Expected FHIR-qualified Encounter type in alias hover: $value")
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
    fun hover_onChoicePropertyAlias_showsPropertyNotCoercion() {
        // ChoicePropertyTest.cql: O.value where O is an alias for [Observation]
        // Hovering over "value" should show property info, not the FHIRHelpers.ToValue wrapper.
        val docId = TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/ChoicePropertyTest.cql")
        val hover = hoverProvider.hover(HoverParams(docId, Position(10, 12)))
        assertNotNull(hover, "Expected hover on choice property 'value'")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(element) O.value:"), "Should show property info: $value")
        assertFalse(value.contains("define function"), "Should not show FHIRHelpers wrapper: $value")
    }

    @Test
    fun hover_onChoicePropertyInWithClause_showsPropertyNotCoercion() {
        // ChoicePropertyWithClause.cql: Screening.value in a with/such-that clause
        // Line 14:       such that Screening.value is not null
        // Column 26 = 'v' of "value"
        val docId = TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/ChoicePropertyWithClause.cql")
        val hover = hoverProvider.hover(HoverParams(docId, Position(14, 26)))
        assertNotNull(hover, "Expected hover on choice property in with clause")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(element) Screening.value:"), "Should show property info: $value")
        assertFalse(value.contains("define function"), "Should not show FHIRHelpers wrapper: $value")
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

        val hover =
            hoverProvider.hover(
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

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithoutQuery.cql"), pos),
            )

        assertNull(hover, "Expected null on 'such that' keyword, got: ${hover?.contents?.right?.value}")
    }

    @Test
    fun hover_onFromKeyword_returnsNull() {
        // FromQuery.cql: `from "Items" Item`
        // Cursor on the "from" keyword should return null.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FromQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "From Clause Test" }
        val query = def.expression as Query

        val source = query.source.first()
        val sourceRange = TrackBacks.toRange(source.locator!!)!!
        val pos = Position(sourceRange.start.line, sourceRange.start.character - 5)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FromQuery.cql"), pos),
            )

        assertNull(hover, "Expected null on 'from' keyword, got: ${hover?.contents?.right?.value}")
    }

    @Test
    fun hover_onFromClauseAlias_returnsItemType() {
        // FromQuery.cql: `from "Items" Item`
        // Hovering over "Item" (the alias) should return the item type.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FromQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "From Clause Test" }
        val query = def.expression as Query

        val source = query.source.first()
        val sourceEnd = TrackBacks.toRange(source.expression!!.locator!!)!!.end
        val pos = Position(sourceEnd.line, sourceEnd.character + 2)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FromQuery.cql"), pos),
            )

        assertNotNull(hover, "Expected hover on from-clause alias, got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(alias) Item:"), "Expected '(alias)' prefix and alias name in from-clause hover: $value")
        assertTrue(value.contains("Integer"), "Expected item type Integer for from-alias hover: $value")
    }

    @Test
    fun hover_onFromClauseSourceExpression_returnsType() {
        // FromQuery.cql: `from "Items" Item`
        // Hovering over "Items" (the source expression) should return the source type.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FromQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "From Clause Test" }
        val query = def.expression as Query

        val source = query.source.first()
        val sourceStart = TrackBacks.toRange(source.expression!!.locator!!)!!.start
        // Position just inside the source expression, after the opening quote
        val pos = Position(sourceStart.line, sourceStart.character + 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FromQuery.cql"), pos),
            )

        assertNotNull(hover, "Expected hover on from-clause source expression, got null")
        val value = hover!!.contents.right.value
        // Should show the expression type (the Items definition), not alias marker
        assertTrue(value.contains("define"), "Expected define syntax for source expression hover: $value")
    }

    @Test
    fun hover_onWhereKeyword_returnsNull() {
        // AllClausesQuery.cql: `where N > 1`
        // Cursor on the "where" keyword should return null.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Where Clause Test" }
        val query = def.expression as Query
        // The ELM locator for where expression starts at the `where` keyword:
        // Locator "7:5-7:15" → LSP Position(6, 4) for first char of 'where'.
        val exprRange = TrackBacks.toRange(query.where!!.locator!!)!!
        val pos = Position(exprRange.start.line, exprRange.start.character)

        // Debug: check ANTLR tree structure
        val parseTree = compilationManager.getParseTree(uri)
        assertNotNull(parseTree, "ANTLR parse tree should not be null")
        val ctx = CqlParseTreeVisitor.findDeepestContext(parseTree!!, pos)
        assertNotNull(ctx, "Deepest ANTLR context should not be null ($pos)")
        assertEquals(
            "WhereClauseContext",
            ctx!!.javaClass.simpleName,
            "Expected WhereClauseContext at $pos",
        )

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql"), pos),
            )

        assertNull(hover, "Expected null on 'where' keyword, got: ${hover?.contents?.right?.value}")
    }

    @Test
    fun hover_onWhereClauseExpression_returnsType() {
        // AllClausesQuery.cql: `where N > 1`
        // Hovering over "N" in the where condition should return the alias type.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Where Clause Test" }
        val query = def.expression as Query
        val whereExpr = query.where ?: fail("Expected where expression")
        val greater = whereExpr as? org.hl7.elm.r1.Greater ?: fail("Expected Greater expression")
        val aliasRef = greater.operand[0] as? AliasRef ?: fail("Expected AliasRef as operand[0]")
        // AliasRef locator at usage site, e.g. "7:11" → LSP Position(6, 10)
        val aliasRange = TrackBacks.toRange(aliasRef.locator!!)!!
        val pos = Position(aliasRange.start.line, aliasRange.start.character)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql"), pos),
            )

        assertNotNull(hover, "Expected hover on where-clause expression, got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("N:"), "Expected alias reference in where-clause hover: $value")
    }

    @Test
    fun hover_onWhereClauseExpression_notSuppressedByAntlrKeywordCheck() {
        // Regression guard: the ANTLR keyword suppression must NOT fire on
        // positions that are actually inside the clause expression, even when
        // findDeepestContext returns the clause context (which can happen when
        // ANTLR child contexts have null `stop` tokens).
        //
        // 1. Keyword position → deepest=WhereClauseContext, hover null
        // 2. Expression position → deepest=NOT WhereClauseContext, hover non-null
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Where Clause Test" }
        val query = def.expression as Query

        // Position 1: on the "where" keyword → WhereClauseContext → null hover
        val keywordPos = Position(6, 4)
        val parseTree = compilationManager.getParseTree(uri)!!
        val kwCtx = CqlParseTreeVisitor.findDeepestContext(parseTree, keywordPos)
        assertEquals(
            "WhereClauseContext",
            kwCtx?.javaClass?.simpleName,
            "At keyword position",
        )
        assertNull(
            hoverProvider.hover(
                HoverParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql"),
                    keywordPos,
                ),
            ),
            "Keyword position should produce null",
        )

        // Position 2: on "N" inside `where N > 1` → deeper context → non-null hover
        val whereExpr = query.where ?: fail("Expected where expression")
        val greater = whereExpr as? org.hl7.elm.r1.Greater ?: fail("Expected Greater")
        val aliasRefLoc = greater.operand[0].locator!!
        val exprRange = TrackBacks.toRange(aliasRefLoc)!!
        val exprPos = Position(exprRange.start.line, exprRange.start.character)
        val exprCtx = CqlParseTreeVisitor.findDeepestContext(parseTree, exprPos)
        assertNotEquals(
            "WhereClauseContext",
            exprCtx?.javaClass?.simpleName,
            "At expression position",
        )
        assertNotNull(
            hoverProvider.hover(
                HoverParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql"),
                    exprPos,
                ),
            ),
            "Expression position should produce non-null hover",
        )
    }

    @Test
    fun hover_onReturnKeyword_returnsNull() {
        // AllClausesQuery.cql: `return N`
        // Cursor on the "return" keyword should return null.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Return Clause Test" }
        val query = def.expression as Query
        // The ReturnClause locator starts at "return"
        val returnRange = TrackBacks.toRange(query.`return`!!.locator!!)!!
        val pos = Position(returnRange.start.line, returnRange.start.character)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql"), pos),
            )

        assertNull(hover, "Expected null on 'return' keyword, got: ${hover?.contents?.right?.value}")
    }

    @Test
    fun hover_onReturnClauseExpression_returnsType() {
        // AllClausesQuery.cql: `return N`
        // Hovering over the expression after "return" should show the alias type.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Return Clause Test" }
        val query = def.expression as Query
        val returnExpr = (query.`return`!! as ReturnClause).expression!!
        val exprRange = TrackBacks.toRange(returnExpr.locator!!)!!
        val pos = Position(exprRange.start.line, exprRange.start.character)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql"), pos),
            )

        assertNotNull(hover, "Expected hover on return-clause expression, got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("N:"), "Expected alias reference in return-clause hover: $value")
    }

    @Test
    fun hover_onLetKeyword_returnsNull() {
        // AllClausesQuery.cql line 13: `define "Let Clause Test": from "Numbers" N let X: N + 1 return X`
        // Cursor on the "let" keyword at column 43 should return null.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql")!!
        compilationManager.compile(uri) // ensure compilation
        val pos = Position(12, 43)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql"), pos),
            )

        assertNull(hover, "Expected null on 'let' keyword, got: ${hover?.contents?.right?.value}")
    }

    @Test
    fun hover_onLetClauseIdentifier_returnsType() {
        // AllClausesQuery.cql line 13: `define "Let Clause Test": from "Numbers" N let X: N + 1 return X`
        // Hovering over "X" (the let identifier) at column 47 should produce a hover.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql")!!
        compilationManager.compile(uri) // ensure compilation
        val pos = Position(12, 47)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql"), pos),
            )

        assertNotNull(hover, "Expected hover on let-clause identifier, got null")
    }

    @Test
    fun hover_onSortKeyword_returnsNull() {
        // AllClausesQuery.cql line 15: `define "Sort Clause Test": from "Numbers" N sort by N`
        // Cursor on the "sort" keyword at column 44 should return null.
        // Note: the CQL compiler does not produce Query ELM for sort clauses,
        // so we test keyword suppression via ANTLR only.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql")!!
        compilationManager.compile(uri) // ensure compilation
        val pos = Position(14, 44)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql"), pos),
            )

        assertNull(hover, "Expected null on 'sort' keyword, got: ${hover?.contents?.right?.value}")
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

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithoutQuery.cql"), pos),
            )

        assertNotNull(hover, "Expected hover on without-clause alias, got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(alias) Extra:"), "Expected '(alias)' prefix and alias name in without-clause hover: $value")
        assertTrue(value.contains("Integer"), "Expected item type Integer for without-alias hover: $value")
    }

    // ---- Phase 8: Expression operator keyword suppression ----

    @Test
    fun hover_onAndKeyword_returnsNull() {
        // BinaryOpQuery.cql: `define "AndExpr": 1 > 0 and 2 > 1`
        // Cursor on the `and` keyword should return null.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/BinaryOpQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "AndExpr" }
        val and = def.expression as And
        val leftEnd = TrackBacks.toRange(and.operand[0].locator!!)!!.end
        val pos = Position(leftEnd.line, leftEnd.character + 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/BinaryOpQuery.cql"), pos),
            )
        assertNull(hover, "Expected null on 'and' keyword, got: ${hover?.contents?.right?.value}")
    }

    @Test
    fun hover_onNotKeyword_returnsNull() {
        // BinaryOpQuery.cql: `define "NotExpr": not (1 > 0)`
        // Cursor on the `not` keyword should return null.
        // Line 4: `define "NotExpr": not (1 > 0)` — `not` starts at char 18.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/BinaryOpQuery.cql")!!

        // Cursor on the first character of `not` at Position(3, 18)
        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/BinaryOpQuery.cql"), Position(3, 18)),
            )
        assertNull(hover, "Expected null on 'not' keyword, got: ${hover?.contents?.right?.value}")
    }

    @Test
    fun hover_onIsNullKeywords_returnsNull() {
        // BinaryOpQuery.cql: `define "IsNullExpr": 1 is null`
        // Both `is` and `null` keywords should return null.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/BinaryOpQuery.cql")!!

        // Cursor on `is` keyword at Position(4, 23)
        val isHover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/BinaryOpQuery.cql"), Position(4, 23)),
            )
        assertNull(isHover, "Expected null on 'is' keyword in 'is null', got: ${isHover?.contents?.right?.value}")

        // Cursor on `null` keyword at Position(4, 26)
        val nullHover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/BinaryOpQuery.cql"), Position(4, 26)),
            )
        assertNull(nullHover, "Expected null on 'null' keyword in 'is null', got: ${nullHover?.contents?.right?.value}")

        // Cursor on the operand `1` at Position(4, 21) should NOT be null
        val operandHover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/BinaryOpQuery.cql"), Position(4, 21)),
            )
        assertNotNull(operandHover, "Expected hover on '1' operand in 'is null', got null")
    }

    @Test
    fun hover_onIsNotNullKeywords_returnsNull() {
        // BinaryOpQuery.cql: `define "IsNotNullExpr": 1 is not null`
        // All three keywords `is`, `not`, `null` should return null.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/BinaryOpQuery.cql")!!

        // Cursor on `is` at Position(5, 26)
        assertNull(
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/BinaryOpQuery.cql"), Position(5, 26)),
            ),
        )

        // Cursor on `not` at Position(5, 29)
        assertNull(
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/BinaryOpQuery.cql"), Position(5, 29)),
            ),
        )

        // Cursor on `null` at Position(5, 33)
        assertNull(
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/BinaryOpQuery.cql"), Position(5, 33)),
            ),
        )
    }

    @Test
    fun hover_onInKeyword_returnsNull() {
        // BinaryOpQuery.cql: `define "InExpr": 1 in {1, 2, 3}`
        // Cursor on `in` keyword should return null.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/BinaryOpQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "InExpr" }
        val inExpr = def.expression as In
        val leftEnd = TrackBacks.toRange(inExpr.operand[0].locator!!)!!.end
        val pos = Position(leftEnd.line, leftEnd.character + 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/BinaryOpQuery.cql"), pos),
            )
        assertNull(hover, "Expected null on 'in' keyword, got: ${hover?.contents?.right?.value}")
    }

    @Test
    fun hover_onIfThenElseKeywords_returnNull() {
        // IfElseQuery.cql: `define "IfExpr": if 1 > 0 then 'yes' else 'no'`
        // All three keywords `if`, `then`, `else` should return null.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/IfElseQuery.cql")!!

        // Cursor on `if` at Position(2, 17)
        assertNull(
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/IfElseQuery.cql"), Position(2, 17)),
            ),
            "Expected null on 'if' keyword",
        )

        // Cursor on `then` at Position(2, 26)
        assertNull(
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/IfElseQuery.cql"), Position(2, 26)),
            ),
            "Expected null on 'then' keyword",
        )

        // Cursor on `else` at Position(2, 37)
        assertNull(
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/IfElseQuery.cql"), Position(2, 37)),
            ),
            "Expected null on 'else' keyword",
        )

        // Cursor on condition `1` at Position(2, 20) should NOT be null
        assertNotNull(
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/IfElseQuery.cql"), Position(2, 20)),
            ),
            "Expected hover on '1' condition operand, got null",
        )
    }

    @Test
    fun hover_onFhirAliasPropertyPathPortionWithCoercion_returnsPropertyType() {
        // ScopeCoercionQuery.cql line 11:
        //   such that FHIRHelpers.ToInterval(E.period) = FHIRHelpers.ToInterval(E2.period)
        // Hovering over `period` in `E.period` should show the property type,
        // not the FHIRHelpers.ToInterval function signature (which the ELM path
        // may resolve to via ExpressionTrackBackVisitor overlap).
        // `period` starts at column 41 (0-indexed), cursor at column 43 (`r` in `period`).
        val posOnPath = Position(10, 43)

        val hover =
            hoverProvider.hover(
                HoverParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/ScopeCoercionQuery.cql"),
                    posOnPath,
                ),
            )

        assertNotNull(hover, "Expected property hover on 'period' in E.period, got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(element) E.period:"), "Expected (element) prefix: $value")
        assertFalse(
            value.contains("define function"),
            "Should NOT show FHIRHelpers function signature: $value",
        )
    }

    @Test
    fun hover_onScopeAliasWithCoercion_showsAliasType() {
        // ScopeCoercionQuery.cql:
        //   [Encounter] E with [Encounter] E2 such that FHIRHelpers.ToInterval(E.period) = FHIRHelpers.ToInterval(E2.period)
        // Hovering over `E` in `E.period` should show alias type (Encounter, from ANTLR tree).
        // The ANTLR classifier correctly identifies `E` as an AliasReference even though
        // the ELM Property locator covers only "period" (not "E.period") when FHIRHelpers
        // wraps the property access in a coercion function.
        //
        // `E` on line 11 (1-indexed) at column 39 (0-indexed, `E` in `E.period`):
        //   `      such that FHIRHelpers.ToInterval(E.period)`
        //                                      ^-- column 39
        val posOnAlias = Position(10, 39)

        val hover =
            hoverProvider.hover(
                HoverParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/ScopeCoercionQuery.cql"),
                    posOnAlias,
                ),
            )

        assertNotNull(hover, "Expected alias hover on 'E' in E.period, got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(alias) E:"), "Expected '(alias) E:' in hover: $value")
        assertTrue(value.contains("FHIR.Encounter"), "Expected FHIR-qualified Encounter type in alias hover: $value")
    }

    @Test
    fun hover_onWithAliasRetrieveSource_showsAliasType() {
        // ScopeCoercionQuery.cql: E2 is a with-clause alias from [Encounter] E2.
        // Hovering over `E2` in `E2.period` should use the ANTLR path.
        // `E2` on line 11 at column 74:
        //   `      such that FHIRHelpers.ToInterval(E.period) = FHIRHelpers.ToInterval(E2.period)`
        //                                                                             ^-- column 74
        val posOnAlias = Position(10, 74)

        val hover =
            hoverProvider.hover(
                HoverParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/ScopeCoercionQuery.cql"),
                    posOnAlias,
                ),
            )

        assertNotNull(hover, "Expected alias hover on 'E2' in E2.period, got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(alias) E2:"), "Expected '(alias) E2:' in hover: $value")
        assertTrue(value.contains("FHIR.Encounter"), "Expected FHIR-qualified Encounter type in alias hover: $value")
    }

    @Test
    fun hover_onAliasDeclarationInExists_withExpressionRefSource() {
        // AliasInExistsQuery.cql line 14:
        //       with "Qualifying Encounter During Measurement Period" QualifyingEncounter
        // Hovering on the alias DECLARATION `QualifyingEncounter` inside an exists()
        // should resolve the type with the model namespace via the ELM path now that
        // findAliasSource traverses through UnaryExpression (Exists).
        // `Q` in `QualifyingEncounter` is at column 60 (0-indexed).
        val posOnAlias = Position(13, 60)

        val hover =
            hoverProvider.hover(
                HoverParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/AliasInExistsQuery.cql"),
                    posOnAlias,
                ),
            )

        assertNotNull(hover, "Expected alias declaration hover on 'QualifyingEncounter' in exists(), got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(alias) QualifyingEncounter:"), "Expected '(alias) QualifyingEncounter:' in hover: $value")
        assertTrue(value.contains("FHIR.Encounter"), "Expected FHIR-qualified Encounter type in alias declaration hover: $value")
    }

    @Test
    fun hover_onAliasDeclarationInExists_withRetrieveSource() {
        // AliasInExistsQuery.cql line 13:
        //   exists ( [Encounter] AdolescentScreening
        // Hovering on the alias DECLARATION `AdolescentScreening` inside an exists()
        // should resolve the type with the model namespace via the ELM path now that
        // findAliasSource traverses through UnaryExpression (Exists).
        // `A` in `AdolescentScreening` is at column 23 (0-indexed).
        val posOnAlias = Position(12, 23)

        val hover =
            hoverProvider.hover(
                HoverParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/AliasInExistsQuery.cql"),
                    posOnAlias,
                ),
            )

        assertNotNull(hover, "Expected alias declaration hover on 'AdolescentScreening' in exists(), got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(alias) AdolescentScreening:"), "Expected '(alias) AdolescentScreening:' in hover: $value")
        assertTrue(value.contains("FHIR.Encounter"), "Expected FHIR-qualified Encounter type in alias declaration hover: $value")
    }

    @Test
    fun hover_onAliasDeclarationInLast_showsFhirQualifiedType() {
        // WithLastQuery.cql line 9:
        //   Last([Encounter] E
        // Hovering on the alias DECLARATION `E` inside a Last() wrapper
        // should resolve the type via the ELM path now that findAliasSource
        // traverses through Last via OperatorExpression.
        // `E` is at column 19 (0-indexed).
        val posOnAlias = Position(9, 19)

        val hover =
            hoverProvider.hover(
                HoverParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithLastQuery.cql"),
                    posOnAlias,
                ),
            )

        assertNotNull(hover, "Expected alias declaration hover on 'E' in Last(), got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(alias) E:"), "Expected '(alias) E:' in hover: $value")
        assertTrue(value.contains("FHIR.Encounter"), "Expected FHIR-qualified Encounter type in alias declaration hover: $value")
    }

    @Test
    fun hover_onSortByImplicitProperty_period_returnsElementType() {
        // SortByImplicitScopeQuery.cql line 10: `    sort by period`
        // Hovering on `period` (implicit scope alias `E`) should show the
        // property type, not ExpressionRef fallthrough.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/SortByImplicitScopeQuery.cql")!!
        compilationManager.compile(uri)
        val pos = Position(10, 12)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/SortByImplicitScopeQuery.cql"), pos),
            )

        assertNotNull(hover, "Expected property hover on 'period' in sort by, got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(element) E.period:"), "Expected '(element) E.period:' in hover: $value")
        assertFalse(value.contains("FHIRHelpers"), "Hover should not contain FHIRHelpers include: $value")
    }

    @Test
    fun hover_onSortByImplicitProperty_effective_returnsChoiceType() {
        // SortByImplicitScopeQuery.cql line 20: `    sort by start of effective`
        // Hovering on `effective` (implicit scope alias `O`, Observation type)
        // should show (element) effective: Choice<dateTime, Period, Timing, instant>.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/SortByImplicitScopeQuery.cql")!!
        compilationManager.compile(uri)
        val pos = Position(20, 21)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/SortByImplicitScopeQuery.cql"), pos),
            )

        assertNotNull(hover, "Expected property hover on 'effective' in sort by, got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(element) O.effective:"), "Expected '(element) O.effective:' in hover: $value")
        assertTrue(value.contains("Choice<"), "Expected Choice type for effective property: $value")
        assertFalse(value.contains("FHIRHelpers"), "Hover should not contain FHIRHelpers include: $value")
    }

    @Test
    fun hover_onSortByDeclaredAlias_returnsAliasType() {
        // AllClausesQuery.cql line 15: `define "Sort Clause Test": from "Numbers" N sort by N`
        // Hovering on the second `N` (the alias reference in sort-by):
        // The classifier now identifies the alias reference directly, and the hover
        // provider resolves it via markupForAlias from the ELM query source clause.
        // `N` is at column 52 on line 14 (0-indexed).
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql")!!
        compilationManager.compile(uri)
        val pos = Position(14, 52)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/AllClausesQuery.cql"), pos),
            )

        assertNotNull(hover, "Expected alias hover on 'N' in sort-by, got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(alias) N:"), "Expected '(alias) N:' in hover: $value")
    }

    @Test
    fun hover_onSortByBareIdentifier_returnsElementType() {
        // SortByImplicitScopeQuery.cql line 15: `    sort by status`
        // Hovering on `status` (implicit scope alias `E`, Encounter type)
        // should show the property type for status on Encounter.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/SortByImplicitScopeQuery.cql")!!
        compilationManager.compile(uri)
        val pos = Position(15, 12)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/SortByImplicitScopeQuery.cql"), pos),
            )

        assertNotNull(hover, "Expected property hover on 'status' in sort by, got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(element) E.status:"), "Expected '(element) E.status:' in hover: $value")
        assertFalse(value.contains("FHIRHelpers"), "Hover should not contain FHIRHelpers include: $value")
    }

    @Test
    fun hover_onUnaryOverload_picksOneArgSignature() {
        // OverloadedFunctions.cql defines three "Add" overloads (1, 2, 3 args).
        // Hovering the call site `"Add"(1)` should pick the one-argument overload.
        val docId = TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/OverloadedFunctions.cql")
        // Line 12: `define "UseUnary": "Add"(1)` — col 20 lands inside `"Add"`
        val hover = hoverProvider.hover(HoverParams(docId, Position(11, 20)))

        assertNotNull(hover, "Expected hover on overloaded function call")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("function Add(x System.Integer):"), "Expected unary overload signature: $value")
        assertFalse(value.contains(", y"), "Should not show two-arg overload: $value")
    }

    @Test
    fun hover_onBinaryOverload_picksTwoArgSignature() {
        val docId = TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/OverloadedFunctions.cql")
        // Line 13: `define "UseBinary": "Add"(1, 2)`
        val hover = hoverProvider.hover(HoverParams(docId, Position(12, 21)))

        assertNotNull(hover, "Expected hover on overloaded function call")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("function Add(x System.Integer, y System.Integer):"), "Expected binary overload signature: $value")
        assertFalse(value.contains(", z"), "Should not show three-arg overload: $value")
    }

    @Test
    fun hover_onTernaryOverload_picksThreeArgSignature() {
        val docId = TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/OverloadedFunctions.cql")
        // Line 14: `define "UseTernary": "Add"(1, 2, 3)`
        val hover = hoverProvider.hover(HoverParams(docId, Position(13, 22)))

        assertNotNull(hover, "Expected hover on overloaded function call")
        val value = hover!!.contents.right.value
        // Three-arg signature renders multi-line, check for z param
        assertTrue(value.contains("z System.Integer"), "Expected ternary overload signature (z param): $value")
    }

    @Test
    fun hover_onRetrieveType_returnsListOfType() {
        // WithFhirQuery.cql: `[Encounter] E` — cursor on the type `Encounter` inside the brackets.
        // The classifier returns CursorCategory.Retrieve and the hover renders the
        // model-resolved list type.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithFhirQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Encounter Period Test" }
        val retrieve = (def.expression as Query).source.first().expression as Retrieve
        val range = TrackBacks.toRange(retrieve.locator!!)!!
        // Cursor at the second character of the retrieve — past `[`, on the type name.
        val pos = Position(range.start.line, range.start.character + 1)

        val hover =
            hoverProvider.hover(
                HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithFhirQuery.cql"), pos),
            )

        assertNotNull(hover, "Expected hover on Retrieve type, got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("```cql"), "Expected cql block in hover: $value")
        assertTrue(value.contains("Encounter"), "Expected Encounter type in hover: $value")
        // Should be a List<...> type since Retrieve returns a list
        assertTrue(value.contains("List<", ignoreCase = false), "Expected List<...> type marker: $value")
    }
}
