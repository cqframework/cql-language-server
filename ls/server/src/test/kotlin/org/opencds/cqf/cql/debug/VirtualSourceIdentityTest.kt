package org.opencds.cqf.cql.debug

import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.debug.Source
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.provider.DefinitionProvider
import org.opencds.cqf.cql.ls.server.service.TestContentService
import org.opencds.cqf.cql.ls.server.utility.TrackBacks
import java.io.InputStream
import java.net.URI

/**
 * Go-to-definition and the debugger must open a library with no workspace file (bundled
 * FHIRHelpers here, npm FHIRCommon in practice) as the SAME document — one `cql-virtual:` URI —
 * so users see one editor tab instead of two seemingly different versions of the library.
 */
class VirtualSourceIdentityTest {
    /** Reads classpath fixtures but never locates a library, forcing the cql-virtual fallback. */
    private class NeverLocatesContentService : ContentService {
        private val delegate = TestContentService()

        override fun locate(
            root: URI,
            libraryIdentifier: VersionedIdentifier,
        ): Set<URI> = emptySet()

        override fun read(uri: URI): InputStream? = delegate.read(uri)
    }

    private class TestServer(
        cm: CqlCompilationManager,
        cs: ContentService,
    ) : CqlDebugServer(cm, cs, mock(IgContextManager::class.java), mock(LibraryResolutionManager::class.java)) {
        init {
            streamingHandler = StreamingBreakpointHandler().also { it.primaryLibraryId = "ScopeCoercionQuery" }
            streamingLaunchUri = CALLER_PATH
        }

        override fun executeLaunchStreaming(args: DebugLaunchArgs) {}

        fun sourceFor(libId: String): Source {
            val method = CqlDebugServer::class.java.getDeclaredMethod("resolveSource", String::class.java)
            method.isAccessible = true
            return method.invoke(this, libId) as Source
        }
    }

    companion object {
        private const val CALLER_PATH = "/org/opencds/cqf/cql/ls/server/ScopeCoercionQuery.cql"
    }

    private val cs = NeverLocatesContentService()
    private val cm = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))

    private fun definitionTargetForFhirHelpersInclude(): String {
        val library = cm.compile(Uris.parseOrNull(CALLER_PATH)!!)!!.library!!
        val includeDef = library.includes!!.def.first { it.path == "FHIRHelpers" }
        val range = TrackBacks.toRange(includeDef.locator!!)!!
        val locations =
            DefinitionProvider(cm, cs).definition(
                DefinitionParams(TextDocumentIdentifier(CALLER_PATH), Position(range.start.line, range.start.character + 1)),
            )
        return locations.first().targetUri
    }

    private fun debuggerPathForFhirHelpers(): String {
        val server = TestServer(cm, cs)
        server.onLibraryEntered(
            "FHIRHelpers",
            VersionedIdentifier().also {
                it.id = "FHIRHelpers"
                it.version = "4.0.1"
            },
        )
        return server.sourceFor("FHIRHelpers").path
    }

    @Test
    fun `go-to-definition then debugger step-in open the same cql-virtual document`() {
        val definitionUri = definitionTargetForFhirHelpersInclude()
        val debuggerPath = debuggerPathForFhirHelpers()

        assertEquals("cql-virtual:///FHIRHelpers-4.0.1.cql", definitionUri)
        assertEquals(definitionUri, debuggerPath)
    }

    @Test
    fun `debugger step-in then go-to-definition open the same cql-virtual document`() {
        val debuggerPath = debuggerPathForFhirHelpers()
        val definitionUri = definitionTargetForFhirHelpersInclude()

        assertEquals(debuggerPath, definitionUri)
        assertNotNull(cm.getSourceText(URI.create(definitionUri)), "Both features should share one compilation-cache entry")
    }
}
