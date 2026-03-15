package org.opencds.cqf.cql.ls.server

import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.services.LanguageClient
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Logger.JavaLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
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
            val compilationManager = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs))
            val languageClientFuture = CompletableFuture<LanguageClient>()
            val commandsFuture = CompletableFuture<List<CommandContribution>>()
            commandsFuture.complete(emptyList())
            server = CqlLanguageServer(
                languageClientFuture,
                CqlWorkspaceService(languageClientFuture, commandsFuture, mutableListOf(), eventBus),
                CqlTextDocumentService(languageClientFuture, HoverProvider(compilationManager), FormattingProvider(cs), eventBus)
            )
        }
    }

    @Test
    fun handshake() {
        assertNotNull(server)
    }

    @Test
    fun hoverInt() {
        val hover = server.getTextDocumentService()
            .hover(HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/Two.cql"), Position(5, 2)))
            .get()

        assertNotNull(hover)
        assertNotNull(hover!!.contents.right)

        val markup = hover.contents.right
        assertEquals("markdown", markup.kind)
        assertEquals("```cql\nSystem.Integer\n```", markup.value)
    }

    @Test
    fun hoverNothing() {
        val hover = server.getTextDocumentService()
            .hover(HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/Two.cql"), Position(2, 0)))
            .get()

        assertNull(hover)
    }

    @Test
    fun hoverList() {
        val hover = server.getTextDocumentService()
            .hover(HoverParams(TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/Two.cql"), Position(8, 2)))
            .get()

        assertNotNull(hover)
        assertNotNull(hover!!.contents.right)

        val markup = hover.contents.right
        assertEquals("markdown", markup.kind)
        assertEquals("```cql\nlist<System.Integer>\n```", markup.value)
    }
}
