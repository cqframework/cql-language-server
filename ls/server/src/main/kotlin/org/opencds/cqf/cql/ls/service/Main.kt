package org.opencds.cqf.cql.ls.service

import org.cqframework.cql.cql2elm.CqlTranslator
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Logger.JavaLogger
import org.opencds.cqf.cql.engine.execution.CqlEngine
import org.opencds.cqf.cql.ls.server.CqlLanguageServer
import org.opencds.cqf.cql.ls.server.command.ExecuteCqlCommandContribution
import org.opencds.cqf.cql.ls.server.command.ViewElmCommandContribution
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.JsonLibraryResolutionConfigProvider
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution
import org.opencds.cqf.cql.ls.server.provider.DefinitionProvider
import org.opencds.cqf.cql.ls.server.provider.DocumentSymbolProvider
import org.opencds.cqf.cql.ls.server.provider.FormattingProvider
import org.opencds.cqf.cql.ls.server.provider.HoverProvider
import org.opencds.cqf.cql.ls.server.provider.ReferencesProvider
import org.opencds.cqf.cql.ls.server.service.ActiveContentService
import org.opencds.cqf.cql.ls.server.service.CqlTextDocumentService
import org.opencds.cqf.cql.ls.server.service.CqlWorkspaceService
import org.opencds.cqf.cql.ls.server.service.DiagnosticsService
import org.opencds.cqf.cql.ls.server.service.FederatedContentService
import org.opencds.cqf.cql.ls.server.service.FileContentService
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

private val log = LoggerFactory.getLogger("org.opencds.cqf.cql.ls.service.Main")

fun main(args: Array<String>) {
    // Capture the real stdout before redirecting it. The LSP channel writes to this
    // reference. After redirect, any library code that calls System.out.println() goes
    // to stderr instead, preventing corruption of the LSP Content-Length framing.
    @Suppress("java:S106")
    val lspOut = System.out
    configureLogging()

    val eventBus = EventBus.builder().logger(JavaLogger("eventBus")).build()
    val workspaceFolders = mutableListOf<WorkspaceFolder>()

    val activeContentService = ActiveContentService().also { eventBus.register(it) }
    val libraryResolutionManager =
        LibraryResolutionManager(workspaceFolders)
            .also { eventBus.register(it) }
    val configProvider =
        JsonLibraryResolutionConfigProvider(workspaceFolders)
            .also { eventBus.register(it) }
    val fileContentService = FileContentService(workspaceFolders, configProvider, libraryResolutionManager)
    val federatedContentService = FederatedContentService(activeContentService, fileContentService)

    val compilerOptionsManager = CompilerOptionsManager(federatedContentService).also { eventBus.register(it) }
    val igContextManager = IgContextManager(federatedContentService).also { eventBus.register(it) }
    val compilationManager =
        CqlCompilationManager(
            federatedContentService,
            compilerOptionsManager,
            igContextManager,
            libraryResolutionManager,
        )

    val languageClientFuture = CompletableFuture<LanguageClient>()
    val commandsFuture = CompletableFuture<List<CommandContribution>>()

    val workspaceService = CqlWorkspaceService(languageClientFuture, commandsFuture, workspaceFolders, eventBus)
    val textDocumentService =
        CqlTextDocumentService(
            languageClientFuture,
            HoverProvider(compilationManager, federatedContentService),
            FormattingProvider(federatedContentService),
            eventBus,
            DefinitionProvider(compilationManager, federatedContentService),
            DocumentSymbolProvider(compilationManager),
            ReferencesProvider(compilationManager, federatedContentService),
        )

    val contributions = mutableListOf<CommandContribution>()
    contributions.add(ViewElmCommandContribution(compilationManager))
    contributions.add(ExecuteCqlCommandContribution(igContextManager, federatedContentService, libraryResolutionManager))
    commandsFuture.complete(contributions)

    val server = CqlLanguageServer(languageClientFuture, workspaceService, textDocumentService)
    DiagnosticsService(languageClientFuture, compilationManager, federatedContentService).also { eventBus.register(it) }

    val launcher = LSPLauncher.createServerLauncher(server, System.`in`, lspOut)
    val client = launcher.remoteProxy

    server.connect(client)
    languageClientFuture.complete(client)
    val serverThread = launcher.startListening()

    log.info("java.version: {}", System.getProperty("java.version"))
    log.info("cql-language-server version: {}", CqlLanguageServer::class.java.`package`.implementationVersion)
    log.info("cql-translator version: {}", CqlTranslator::class.java.`package`.implementationVersion)
    log.info("cql-engine version: {}", CqlEngine::class.java.`package`.implementationVersion)
    log.info("cql-language-server started")

    val executor = Executors.newSingleThreadExecutor()
    val connectionClosed =
        CompletableFuture.runAsync(
            {
                try {
                    serverThread.get()
                    log.info("LSP connection closed")
                } catch (e: Exception) {
                    log.debug("Server thread exception", e)
                }
            },
            executor,
        )

    try {
        CompletableFuture.anyOf(server.exited(), connectionClosed).get()
    } catch (e: Exception) {
        log.error("Error waiting for shutdown", e)
    }

    log.info("Shutting down language server")
    serverThread.cancel(true)
    executor.shutdownNow()

    System.exit(0)
}

private fun configureLogging() {
    // Redirect System.out to System.err so library code that writes directly to
    // stdout (HAPI FHIR startup messages, CQL compiler diagnostics, etc.) goes to
    // stderr instead of corrupting the LSP Content-Length framing. The real stdout
    // reference is captured in main() before this call and passed to the launcher.
    @Suppress("java:S106")
    System.setOut(System.err)
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()
}
