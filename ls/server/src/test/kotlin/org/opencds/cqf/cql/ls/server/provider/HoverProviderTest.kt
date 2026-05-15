package org.opencds.cqf.cql.ls.server.provider

import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.hl7.elm.r1.Add
import org.hl7.elm.r1.AliasRef
import org.hl7.elm.r1.Code
import org.hl7.elm.r1.ExpressionDef
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
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

    @Disabled("Disabled until LibraryManager caching issues are resolved")
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
        assertTrue(hover.contents.right.value.contains("```cql"))
    }

    @Test
    fun hover_onFunctionDef_returnsReturnType() {
        // Hovering over the function definition itself shows only the return type —
        // the signature is already visible in the source code.
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
        assertTrue(hover!!.contents.right.value.contains("```cql"))
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
        assertTrue(value.contains("(function)"), "Expected '(function)' in hover: $value")
        assertTrue(value.contains("\"Double\""), "Expected function name in hover: $value")
        assertTrue(value.contains("("), "Expected parameter list in hover: $value")
        assertTrue(!value.contains("// from"), "Same-library ref should not include a 'from' comment: $value")
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
        assertTrue(value.contains("(define)"), "Expected '(define)' in hover: $value")
        assertTrue(value.contains("\"MyValue\""), "Expected define name in hover: $value")
        assertTrue(value.contains("FunctionLib"), "Expected library source in hover: $value")
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

        val hover = hoverProvider.hover(
            HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithQuery.cql"), pos),
        )

        assertNotNull(hover)
        val value = hover!!.contents.right.value
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
        assertTrue(value.contains("(function)"), "Expected '(function)' kind in hover: $value")
        assertTrue(value.contains("\"Double\""), "Expected function name in hover: $value")
        assertTrue(value.contains("FunctionLib"), "Expected source library in hover: $value")
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
        assertTrue(hover!!.contents.right.value.contains("```cql"))
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

        val hover = hoverProvider.hover(
            HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithTerminology.cql"), pos),
        )

        assertNotNull(hover)
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(valueset)"), "Expected '(valueset)' kind: $value")
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

        val hover = hoverProvider.hover(
            HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithTerminology.cql"), pos),
        )

        assertNotNull(hover)
        val value = hover!!.contents.right.value
        assertTrue(value.contains("(codesystem)"), "Expected '(codesystem)' kind: $value")
        assertTrue(value.contains("\"SNOMEDCT\""), "Expected codesystem name: $value")
        assertTrue(value.contains("http://snomed.info/sct"), "Expected codesystem URL: $value")
    }

    @Test
    fun hover_onScopePropertyAliasPortion_returnsNull() {
        // WithQuery.cql: "Alias Property Scope Test" returns T.name where T is a tuple alias.
        // The CQL compiler stores this as Property(scope="T", path="name"), not Property(source=AliasRef("T")).
        // Hovering over "T" in "T.name" must return null — showing the Property's result type
        // (System.String) would be misleading; the user is looking at the alias, not the path.
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithQuery.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val def = library.statements!!.def.first { it.name == "Alias Property Scope Test" }
        val query = def.expression as Query
        val property = (query.`return`!! as ReturnClause).expression as Property
        val range = TrackBacks.toRange(property.locator!!)!!
        // Cursor at the very start of the Property range = on the alias "T"
        val posOnAlias = Position(range.start.line, range.start.character)

        val hover = hoverProvider.hover(
            HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithQuery.cql"), posOnAlias),
        )

        assertNull(hover, "Expected null hover on scope-alias portion of T.name, got: ${hover?.contents?.right?.value}")
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

        val hover = hoverProvider.hover(
            HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithQuery.cql"), posOnPath),
        )

        assertNotNull(hover, "Expected hover on path portion of T.name")
        assertTrue(hover!!.contents.right.value.contains("```cql"))
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

        val hover = hoverProvider.hover(
            HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithQuery.cql"), pos),
        )

        assertNotNull(hover, "Expected hover on with-clause alias, got null")
        val value = hover!!.contents.right.value
        assertTrue(value.contains("Integer"), "Expected item type Integer for with-alias hover: $value")
    }

    @Test
    fun hover_onFhirAliasPropertyAliasPortion_returnsNull() {
        // WithFhirQuery.cql: "Encounter Period Test" returns E.period where E is a FHIR Encounter.
        // The compiler wraps E.period in FHIRHelpers.ToInterval(...), and the inner Property's
        // locator only covers "period", not "E". Hovering over "E" must return null — showing
        // the ToInterval function signature would be misleading.
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

        assertNull(hover, "Expected null hover on alias 'E' in E.period, got: ${hover?.contents?.right?.value}")
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
        assertTrue(hover!!.contents.right.value.contains("```cql"), "Expected cql type block: ${hover.contents.right.value}")
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
        assertTrue(value.contains("(valueset)"), "Expected '(valueset)' label: $value")
        assertTrue(value.contains("Ambulatory Encounter"), "Expected valueset name: $value")
        assertTrue(value.contains("http://cts.nlm.nih.gov"), "Expected valueset URL: $value")
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
            hover!!.contents.right.value.contains("fluent function"),
            "Expected '(fluent function)' in hover: ${hover.contents.right.value}",
        )
    }

    // -----------------------------------------------------------------------
    // markup() — unit tests for the public markup helper
    // -----------------------------------------------------------------------

    @Test
    fun markup_nullDef_returnsNull() {
        assertNull(hoverProvider.markup(null))
    }

    @Test
    fun markup_defWithNoExpression_returnsNull() {
        val def = ExpressionDef()
        // expression is null by default; resultType is irrelevant
        assertNull(hoverProvider.markup(def))
    }

    @Test
    fun markup_compiledDef_returnsMarkdownContent() {
        // Compile One.cql to get a real ExpressionDef with expression and resultType
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        val compiler = compilationManager.compile(uri)!!
        val def = compiler.compiledLibrary!!.library!!.statements!!.def.first()

        val result = hoverProvider.markup(def)

        assertNotNull(result)
        assertEquals("markdown", result!!.kind)
        assertTrue(result.value.contains("(define)"), "Expected '(define)' kind in markup: ${result.value}")
        assertTrue(result.value.contains("\"One\""), "Expected define name in markup: ${result.value}")
    }
}
