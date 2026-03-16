package org.opencds.cqf.cql.ls.server.service

import com.google.common.collect.ImmutableList
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams
import org.eclipse.lsp4j.ExecuteCommandOptions
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.FileSystemWatcher
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.RelativePattern
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.Unregistration
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.WorkspaceFoldersOptions
import org.eclipse.lsp4j.WorkspaceServerCapabilities
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.WorkspaceService
import org.greenrobot.eventbus.EventBus
import org.opencds.cqf.cql.ls.server.Constants
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution
import org.opencds.cqf.cql.ls.server.utility.Futures
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

class CqlWorkspaceService(
    private val client: CompletableFuture<LanguageClient>,
    private val commandContributions: CompletableFuture<List<CommandContribution>>,
    private val workspaceFolders: MutableList<WorkspaceFolder>,
    private val eventBus: EventBus
) : WorkspaceService {

    companion object {
        private val log = LoggerFactory.getLogger(CqlWorkspaceService::class.java)
        private val basicWatchers = listOf("**/cql-options.json", "ig.ini")
    }

    @Suppress("java:S125") // Keeping the commented code for future reference
    fun initialize(params: InitializeParams, serverCapabilities: ServerCapabilities) {
        addFolders(params.workspaceFolders)

        val wsc = WorkspaceServerCapabilities()

        // Register for workspace change notifications
        val wfo = WorkspaceFoldersOptions()
        wfo.setChangeNotifications(true)
        wsc.workspaceFolders = wfo

        // Register for file change notifications
        // FileOperationsServerCapabilities fosc = new FileOperationsServerCapabilities();
        // wsc.setFileOperations(fosc);

        // Project symbol search
        // serverCapabilities.setWorkspaceSymbolProvider(true);

        // Set workspace capabilities
        serverCapabilities.workspace = wsc

        // Register commands
        serverCapabilities.executeCommandProvider = ExecuteCommandOptions(getSupportedCommands())
    }

    fun initialized() {
        // Add startup logic here. For example, subscribe the EventBus

        client.join().unregisterCapability(
            UnregistrationParams(
                listOf(Unregistration(
                    Constants.WORKSPACE_DID_CHANGE_WATCHED_FILES_ID,
                    Constants.WORKSPACE_DID_CHANGE_WATCHED_FILES_METHOD
                ))
            )
        )

        val watchers = basicWatchers
            .map { Either.forLeft<String, RelativePattern>(it) }
            .map { FileSystemWatcher(it) }

        val registrationOptions = DidChangeWatchedFilesRegistrationOptions(watchers)

        client.join().registerCapability(
            RegistrationParams(
                listOf(Registration(
                    Constants.WORKSPACE_DID_CHANGE_WATCHED_FILES_ID,
                    Constants.WORKSPACE_DID_CHANGE_WATCHED_FILES_METHOD,
                    registrationOptions
                ))
            )
        )
    }

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        return try {
            executeCommandFromContributions(params)
        } catch (e: Exception) {
            log.error("executeCommand for ${params.command}", e)
            client.join().showMessage(
                MessageParams(MessageType.Error, "Command ${params.command} failed with: ${e.message}")
            )
            Futures.failed(e)
        }
    }

    override fun didChangeWorkspaceFolders(params: DidChangeWorkspaceFoldersParams) {
        try {
            addFolders(params.event.added)
            removeFolders(params.event.removed)
        } catch (e: Exception) {
            log.error("didChangeWorkspaceFolders", e)
        }
    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        // No extension configuration as of yet
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        eventBus.post(DidChangeWatchedFilesEvent(params))
    }

    private fun addFolders(folders: List<WorkspaceFolder>?) {
        folders?.forEach { workspaceFolders.add(it) }
    }

    private fun removeFolders(folders: List<WorkspaceFolder>?) {
        folders?.forEach { workspaceFolders.remove(it) }
    }

    protected fun executeCommandFromContributions(params: ExecuteCommandParams): CompletableFuture<Any> {
        val command = params.command

        for (commandContribution in commandContributions.join()) {
            if (commandContribution.getCommands().contains(command)) {
                return commandContribution.executeCommand(params)
            }
        }

        client.join().showMessage(MessageParams(MessageType.Error, "Unknown Command $command"))
        return CompletableFuture.completedFuture(null)
    }

    fun getSupportedCommands(): List<String> {
        val commands = mutableSetOf<String>()
        for (commandContribution in commandContributions.join()) {
            for (command in commandContribution.getCommands()) {
                if (commands.contains(command)) {
                    throw IllegalArgumentException("The command $command was contributed multiple times")
                }
                commands.add(command)
            }
        }
        return ImmutableList.copyOf(commands)
    }

    fun stop() {
        // Add shutdown logic here. For example, unsubscribe the EventBus
    }
}
