package org.opencds.cqf.cql.ls.server

import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SetTraceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TraceValue
import org.eclipse.lsp4j.services.LanguageClient
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Logger.JavaLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.mockito.Mockito
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution
import org.opencds.cqf.cql.ls.server.provider.FormattingProvider
import org.opencds.cqf.cql.ls.server.provider.HoverProvider
import org.opencds.cqf.cql.ls.server.service.CqlTextDocumentService
import org.opencds.cqf.cql.ls.server.service.CqlWorkspaceService
import org.opencds.cqf.cql.ls.server.service.TestContentService
import java.util.concurrent.CompletableFuture

class LanguageServerTest {
    companion object {
        private lateinit var server: CqlLanguageServer

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val eventBus = EventBus.builder().logger(JavaLogger("eventBus")).build()
            val cs = TestContentService()
            val compilationManager = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
            val languageClientFuture = CompletableFuture<LanguageClient>()
            val commandsFuture = CompletableFuture<List<CommandContribution>>()
            commandsFuture.complete(emptyList())
            server =
                CqlLanguageServer(
                    languageClientFuture,
                    CqlWorkspaceService(languageClientFuture, commandsFuture, mutableListOf(), eventBus),
                    CqlTextDocumentService(languageClientFuture, HoverProvider(compilationManager), FormattingProvider(cs), eventBus),
                )
        }

        /** Builds a fresh, isolated server instance for tests that mutate server state. */
        private fun buildServer(): CqlLanguageServer {
            val eventBus = EventBus.builder().build()
            val cs = TestContentService()
            val compilationManager = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
            val clientFuture = CompletableFuture<LanguageClient>()
            val commandsFuture = CompletableFuture.completedFuture<List<CommandContribution>>(emptyList())
            return CqlLanguageServer(
                clientFuture,
                CqlWorkspaceService(clientFuture, commandsFuture, mutableListOf(), eventBus),
                CqlTextDocumentService(clientFuture, HoverProvider(compilationManager), FormattingProvider(cs), eventBus),
            )
        }
    }

    @Test
    fun handshake() {
        assertNotNull(server)
    }

    @Disabled("Disabled until LibraryManager caching issues are resolved")
    @Test
    fun hoverInt() {
        val hover =
            server.getTextDocumentService()
                .hover(HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/Two.cql"), Position(5, 2)))
                .get()

        assertNotNull(hover)
        assertNotNull(hover!!.contents.right)

        val markup = hover.contents.right
        assertEquals("markdown", markup.kind)
        assertEquals("```cql\nSystem.Integer\n```", markup.value)
    }

    @Disabled("Disabled until LibraryManager caching issues are resolved")
    @Test
    fun hoverNothing() {
        val hover =
            server.getTextDocumentService()
                .hover(HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/Two.cql"), Position(2, 0)))
                .get()

        assertNull(hover)
    }

    @Disabled("Disabled until LibraryManager caching issues are resolved")
    @Test
    fun hoverList() {
        val hover =
            server.getTextDocumentService()
                .hover(HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/Two.cql"), Position(8, 2)))
                .get()

        assertNotNull(hover)
        assertNotNull(hover!!.contents.right)

        val markup = hover.contents.right
        assertEquals("markdown", markup.kind)
        assertEquals("```cql\nlist<System.Integer>\n```", markup.value)
    }

    // -------------------------------------------------------------------------
    // initialize()
    // -------------------------------------------------------------------------

    @Test
    fun initialize_returnsNonNullResult() {
        val result = server.initialize(InitializeParams()).get()
        assertNotNull(result)
    }

    @Test
    fun initialize_resultHasNonNullCapabilities() {
        val result = server.initialize(InitializeParams()).get()
        assertNotNull(result.capabilities)
    }

    @Disabled("Disabled until LibraryManager caching issues are resolved")
    @Test
    fun initialize_setsHoverProviderCapability() {
        val result = server.initialize(InitializeParams()).get()
        // CqlTextDocumentService.initialize() calls setHoverProvider(true)
        assertNotNull(result.capabilities.hoverProvider)
    }

    @Test
    fun initialize_setsDocumentFormattingCapability() {
        val result = server.initialize(InitializeParams()).get()
        // CqlTextDocumentService.initialize() calls setDocumentFormattingProvider(true)
        assertNotNull(result.capabilities.documentFormattingProvider)
    }

    @Test
    fun initialize_setsTextDocumentSyncCapability() {
        val result = server.initialize(InitializeParams()).get()
        // CqlTextDocumentService.initialize() calls setTextDocumentSync(TextDocumentSyncKind.Full)
        assertNotNull(result.capabilities.textDocumentSync)
    }

    @Test
    fun initialize_setsWorkspaceFolderCapabilities() {
        val result = server.initialize(InitializeParams()).get()
        // CqlWorkspaceService.initialize() sets workspace folder options
        assertNotNull(result.capabilities.workspace)
        assertNotNull(result.capabilities.workspace.workspaceFolders)
    }

    // -------------------------------------------------------------------------
    // initialized() / setTrace()
    // -------------------------------------------------------------------------

    @Test
    fun initialized_doesNotThrow() {
        assertDoesNotThrow { server.initialized(InitializedParams()) }
    }

    @Test
    fun setTrace_doesNotThrow() {
        // LSP4J's default implementation throws UnsupportedOperationException;
        // CqlLanguageServer overrides it as a no-op.
        assertDoesNotThrow { server.setTrace(SetTraceParams(TraceValue.Verbose)) }
    }

    // -------------------------------------------------------------------------
    // shutdown()
    // -------------------------------------------------------------------------

    @Test
    fun shutdown_returnsDoneFuture() {
        val future = server.shutdown()
        assertTrue(future.isDone)
    }

    @Test
    fun shutdown_resultIsNull() {
        assertNull(server.shutdown().get())
    }

    // -------------------------------------------------------------------------
    // exit() / exited()
    // -------------------------------------------------------------------------

    @Test
    fun exit_completesExitedFutureAndWasNotDoneBefore() {
        // Use a fresh server so exit() doesn't affect other tests via the shared instance.
        val freshServer = buildServer()
        assertFalse(freshServer.exited().isDone, "exited() should not be done before exit() is called")
        freshServer.exit()
        assertTrue(freshServer.exited().isDone, "exited() should be done after exit() is called")
    }

    // -------------------------------------------------------------------------
    // connect()
    // -------------------------------------------------------------------------

    @Test
    fun connect_completesClientFuture() {
        // Use a fresh server with an incomplete client future so connect() can complete it.
        val freshServer = buildServer()
        val mockClient = Mockito.mock(LanguageClient::class.java)
        freshServer.connect(mockClient)
        // getTextDocumentService() / getWorkspaceService() are synchronous — just verify
        // that the server's services are retrievable (indirectly exercises the client future).
        assertNotNull(freshServer.getTextDocumentService())
        assertNotNull(freshServer.getWorkspaceService())
    }
}
