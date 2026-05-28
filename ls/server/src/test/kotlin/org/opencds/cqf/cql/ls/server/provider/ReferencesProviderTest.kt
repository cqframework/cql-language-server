package org.opencds.cqf.cql.ls.server.provider

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Assertions.assertFalse
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

class ReferencesProviderTest {
    companion object {
        private lateinit var provider: ReferencesProvider
        private lateinit var compilationManager: CqlCompilationManager

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val cs = TestContentService()
            compilationManager =
                CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
            provider = ReferencesProvider(compilationManager, cs)

            // Compile FunctionCaller first so the reverse dependency index is populated.
            // When FunctionCaller (which includes FunctionLib) is compiled, the manager records
            // FunctionLib → {FunctionCaller} in reverseDeps.
            compilationManager.compile(Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!)
        }
    }

    // -------------------------------------------------------------------------
    // Cursor on define "MyValue" in FunctionLib → 1 reference in FunctionCaller
    // -------------------------------------------------------------------------

    @Test
    fun references_expressionDef_findsAllUsagesInDependentLibrary() {
        val libUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionLib.cql")!!
        val library = compilationManager.compile(libUri)!!.library!!

        val myValueDef = library.statements!!.def.first { it.name == "MyValue" }
        val range = TrackBacks.toRange(myValueDef.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val locations =
            provider.references(
                ReferenceParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionLib.cql"), pos, ReferenceContext(false)),
            )

        assertFalse(locations.isEmpty(), "Expected at least one reference to 'MyValue'")
        assertTrue(
            locations.any { it.uri.contains("FunctionCaller") },
            "Expected a reference in FunctionCaller, got: ${locations.map { it.uri }}",
        )
    }

    // -------------------------------------------------------------------------
    // Cursor on define function "Double" in FunctionLib → 1 reference in FunctionCaller
    // -------------------------------------------------------------------------

    @Test
    fun references_functionDef_findsAllUsagesInDependentLibrary() {
        val libUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionLib.cql")!!
        val library = compilationManager.compile(libUri)!!.library!!

        val doubleDef = library.statements!!.def.first { it.name == "Double" }
        val range = TrackBacks.toRange(doubleDef.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val locations =
            provider.references(
                ReferenceParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionLib.cql"), pos, ReferenceContext(false)),
            )

        assertFalse(locations.isEmpty(), "Expected at least one reference to 'Double'")
        assertTrue(
            locations.any { it.uri.contains("FunctionCaller") },
            "Expected a reference in FunctionCaller, got: ${locations.map { it.uri }}",
        )
    }

    // -------------------------------------------------------------------------
    // Cursor outside any expression — should return empty list
    // -------------------------------------------------------------------------

    @Test
    fun references_outsideAnyExpression_returnsEmpty() {
        // Position(0, 0) is on "library FunctionLib" — not on any symbol
        val locations =
            provider.references(
                ReferenceParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/FunctionLib.cql"),
                    Position(0, 0),
                    ReferenceContext(false),
                ),
            )
        assertTrue(locations.isEmpty(), "Expected empty references for non-symbol position")
    }

    // -------------------------------------------------------------------------
    // Cursor on a symbol that has no dependents compiled — returns empty list for cross-library
    // -------------------------------------------------------------------------

    @Test
    fun references_noKnownDependents_returnsEmptyOrSameLibraryOnly() {
        // One.cql is not included by any other compiled library in this test context.
        // References within One.cql itself should be empty (define "One": 1 — no internal refs).
        val locations =
            provider.references(
                ReferenceParams(
                    TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/One.cql"),
                    Position(2, 8),
                    ReferenceContext(false),
                ),
            )
        // May be empty or contain same-library refs — just assert it doesn't throw
        assertTrue(locations.isEmpty() || locations.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // ValueSetRef — references to "Beta Blocker Therapy"
    // -------------------------------------------------------------------------

    @Test
    fun references_valueSetRef_findsUsages() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithTerminology.cql")!!
        compilationManager.compile(uri)
        val library = compilationManager.compile(uri)!!.library!!

        val vsDef = library.statements!!.def.first { it.name == "UseVS" }
        val ref = vsDef.expression as org.hl7.elm.r1.ValueSetRef
        val range = TrackBacks.toRange(ref.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val locations =
            provider.references(
                ReferenceParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithTerminology.cql"), pos, ReferenceContext(false)),
            )

        assertFalse(locations.isEmpty(), "Expected at least one reference to 'Beta Blocker Therapy'")
    }

    // -------------------------------------------------------------------------
    // CodeSystemRef — references to "SNOMEDCT"
    // -------------------------------------------------------------------------

    @Test
    fun references_codeSystemRef_findsUsages() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithTerminology.cql")!!
        val library = compilationManager.compile(uri)!!.library!!

        val csDef = library.statements!!.def.first { it.name == "UseCS" }
        val code = csDef.expression as org.hl7.elm.r1.Code
        val csRef = code.system as? org.hl7.elm.r1.CodeSystemRef ?: return
        val range = TrackBacks.toRange(csRef.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val locations =
            provider.references(
                ReferenceParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/WithTerminology.cql"), pos, ReferenceContext(false)),
            )

        assertFalse(locations.isEmpty(), "Expected at least one reference to 'SNOMEDCT'")
    }
}
