package org.opencds.cqf.cql.ls.server

import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.SetTraceParams
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import org.opencds.cqf.cql.ls.server.service.CqlTextDocumentService
import org.opencds.cqf.cql.ls.server.service.CqlWorkspaceService
import org.opencds.cqf.cql.ls.server.utility.Futures
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

class CqlLanguageServer(
    private val client: CompletableFuture<LanguageClient>,
    private val workspaceService: CqlWorkspaceService,
    private val textDocumentService: CqlTextDocumentService,
) : LanguageServer, LanguageClientAware {
    companion object {
        private val log = LoggerFactory.getLogger(CqlLanguageServer::class.java)
    }

    private val exited = CompletableFuture<Void>()

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        return try {
            val serverCapabilities = ServerCapabilities()
            workspaceService.initialize(params, serverCapabilities)
            textDocumentService.initialize(params, serverCapabilities)

            val result = InitializeResult()
            result.capabilities = serverCapabilities
            CompletableFuture.completedFuture(result)
        } catch (e: Exception) {
            log.error("failed to initialize with error: {}", e.message)
            Futures.failed(e)
        }
    }

    override fun initialized(params: InitializedParams) {
        workspaceService.initialized()
    }

    override fun setTrace(params: SetTraceParams) {
        // No-op: VS Code sends $/setTrace on startup; suppress the UnsupportedOperationException
        // from the LSP4J default implementation.
    }

    override fun shutdown(): CompletableFuture<Any> {
        // Nothing to do currently
        return CompletableFuture.completedFuture(null)
    }

    override fun exit() {
        exited.complete(null)
    }

    fun exited(): CompletableFuture<Void> = exited

    override fun getTextDocumentService(): TextDocumentService = textDocumentService

    override fun getWorkspaceService(): WorkspaceService = workspaceService

    override fun connect(client: LanguageClient) {
        this.client.complete(client)
    }
}
