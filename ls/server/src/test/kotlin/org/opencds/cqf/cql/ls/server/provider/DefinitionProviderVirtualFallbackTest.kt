package org.opencds.cqf.cql.ls.server.provider

import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.hl7.elm.r1.Equal
import org.hl7.elm.r1.FunctionRef
import org.hl7.elm.r1.Query
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService
import org.opencds.cqf.cql.ls.server.utility.TrackBacks
import java.io.InputStream
import java.net.URI

/**
 * A [ContentService] that reads test fixtures from the classpath (like [TestContentService]) but
 * never *locates* anything — simulating a real project where a referenced library (e.g.
 * FHIRHelpers) has no file under `input/cql`, forcing [DefinitionProvider] down its
 * `cql-virtual:` fallback path.
 */
private class NeverLocatesContentService : ContentService {
    private val delegate = TestContentService()

    override fun locate(
        root: URI,
        libraryIdentifier: VersionedIdentifier,
    ): Set<URI> = emptySet()

    override fun read(uri: URI): InputStream? = delegate.read(uri)
}

class DefinitionProviderVirtualFallbackTest {
    companion object {
        private lateinit var provider: DefinitionProvider
        private lateinit var compilationManager: CqlCompilationManager

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val cs = NeverLocatesContentService()
            compilationManager =
                CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
            provider = DefinitionProvider(compilationManager, cs)
        }
    }

    @Test
    fun `FunctionRef into a library with no workspace file resolves via a cql-virtual URI`() {
        val callerUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/ScopeCoercionQuery.cql")!!
        val library = compilationManager.compile(callerUri)!!.library!!

        val queryDef = library.statements!!.def.first { it.name == "With Test" }
        val query = queryDef.expression as Query
        val suchThat = query.relationship.first().suchThat as Equal
        val ref = suchThat.operand[0] as FunctionRef
        val range = TrackBacks.toRange(ref.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val locations =
            provider.definition(
                DefinitionParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/ScopeCoercionQuery.cql"), pos),
            )

        assertFalse(locations.isEmpty(), "Expected a location for FHIRHelpers.ToInterval via the virtual-URI fallback")
        val loc = locations.first()
        assertTrue(loc.targetUri.startsWith("cql-virtual:"), "Expected a cql-virtual: target URI, got: ${loc.targetUri}")
        assertTrue(loc.targetUri.contains("FHIRHelpers"), "Expected the virtual URI to reference FHIRHelpers, got: ${loc.targetUri}")
    }

    @Test
    fun `IncludeDef for a library with no workspace file resolves via a cql-virtual URI`() {
        val callerUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/ScopeCoercionQuery.cql")!!
        val library = compilationManager.compile(callerUri)!!.library!!

        val includeDef = library.includes!!.def.first { it.path == "FHIRHelpers" }
        val range = TrackBacks.toRange(includeDef.locator!!)!!
        val pos = Position(range.start.line, range.start.character + 1)

        val locations =
            provider.definition(
                DefinitionParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/ScopeCoercionQuery.cql"), pos),
            )

        assertFalse(locations.isEmpty(), "Expected a location for the FHIRHelpers IncludeDef via the virtual-URI fallback")
        val loc = locations.first()
        assertTrue(loc.targetUri.startsWith("cql-virtual:"), "Expected a cql-virtual: target URI, got: ${loc.targetUri}")
        assertEquals(0, loc.targetRange.start.line, "IncludeDef should navigate to line 0 of the virtual document")
    }
}
