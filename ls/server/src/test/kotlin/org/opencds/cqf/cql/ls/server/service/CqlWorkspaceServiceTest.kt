package org.opencds.cqf.cql.ls.server.service

import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent
import org.eclipse.lsp4j.services.LanguageClient
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution
import java.util.concurrent.CompletableFuture

class CqlWorkspaceServiceTest {
    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Minimal CommandContribution whose executeCommand() returns "<command>_result". */
    private fun fakeContribution(vararg commands: String): CommandContribution =
        object : CommandContribution {
            override fun getCommands() = setOf(*commands)

            override fun executeCommand(params: ExecuteCommandParams) = CompletableFuture.completedFuture<Any>("${params.command}_result")
        }

    private fun buildService(
        contributions: List<CommandContribution> = emptyList(),
        client: LanguageClient = Mockito.mock(LanguageClient::class.java),
        folders: MutableList<WorkspaceFolder> = mutableListOf(),
        eventBus: EventBus = EventBus.builder().build(),
    ): CqlWorkspaceService =
        CqlWorkspaceService(
            CompletableFuture.completedFuture(client),
            CompletableFuture.completedFuture(contributions),
            folders,
            eventBus,
        )

    // -------------------------------------------------------------------------
    // initialize()
    // -------------------------------------------------------------------------

    @Test
    fun `initialize enables workspace folder change notifications`() {
        val caps = ServerCapabilities()
        buildService().initialize(InitializeParams(), caps)
        // WorkspaceFoldersOptions.changeNotifications is Either<String, Boolean>; boolean is on the right.
        // Use assertEquals to avoid the assertTrue(Boolean?) nullable ambiguity in Kotlin.
        assertEquals(true, caps.workspace.workspaceFolders.changeNotifications.right)
    }

    @Test
    fun `initialize registers contributed commands in executeCommandProvider`() {
        val caps = ServerCapabilities()
        buildService(listOf(fakeContribution("cmd.a", "cmd.b"))).initialize(InitializeParams(), caps)
        assertTrue(caps.executeCommandProvider.commands.containsAll(listOf("cmd.a", "cmd.b")))
    }

    @Test
    fun `initialize adds workspace folders from InitializeParams`() {
        val folders = mutableListOf<WorkspaceFolder>()
        val params = InitializeParams()
        params.workspaceFolders = listOf(WorkspaceFolder("file:///ws", "ws"))
        buildService(folders = folders).initialize(params, ServerCapabilities())
        assertEquals(1, folders.size)
        assertEquals("file:///ws", folders[0].uri)
    }

    // -------------------------------------------------------------------------
    // initialized()
    // -------------------------------------------------------------------------

    @Test
    fun `initialized calls unregisterCapability then registerCapability on the client`() {
        val mockClient = Mockito.mock(LanguageClient::class.java)
        buildService(client = mockClient).initialized()
        Mockito.verify(mockClient).unregisterCapability(Mockito.any())
        Mockito.verify(mockClient).registerCapability(Mockito.any())
    }

    // -------------------------------------------------------------------------
    // getSupportedCommands()
    // -------------------------------------------------------------------------

    @Test
    fun `getSupportedCommands returns the union of all contributed commands`() {
        val svc = buildService(listOf(fakeContribution("cmd.a"), fakeContribution("cmd.b")))
        val commands = svc.getSupportedCommands()
        assertTrue(commands.containsAll(listOf("cmd.a", "cmd.b")))
    }

    @Test
    fun `getSupportedCommands returns empty list when there are no contributions`() {
        assertTrue(buildService().getSupportedCommands().isEmpty())
    }

    @Test
    fun `getSupportedCommands throws IllegalArgumentException on duplicate command`() {
        val svc = buildService(listOf(fakeContribution("cmd.dup"), fakeContribution("cmd.dup")))
        assertThrows<IllegalArgumentException> { svc.getSupportedCommands() }
    }

    // -------------------------------------------------------------------------
    // didChangeWorkspaceFolders()
    // -------------------------------------------------------------------------

    @Test
    fun `didChangeWorkspaceFolders adds new folders`() {
        val folders = mutableListOf<WorkspaceFolder>()
        val svc = buildService(folders = folders)
        val added = WorkspaceFolder("file:///new", "new")
        val changeEvent = WorkspaceFoldersChangeEvent()
        changeEvent.added = listOf(added)
        changeEvent.removed = emptyList()
        val params = DidChangeWorkspaceFoldersParams()
        params.event = changeEvent
        svc.didChangeWorkspaceFolders(params)
        assertTrue(folders.contains(added))
    }

    @Test
    fun `didChangeWorkspaceFolders removes existing folders`() {
        val existing = WorkspaceFolder("file:///old", "old")
        val folders = mutableListOf(existing)
        val svc = buildService(folders = folders)
        val changeEvent = WorkspaceFoldersChangeEvent()
        changeEvent.added = emptyList()
        changeEvent.removed = listOf(existing)
        val params = DidChangeWorkspaceFoldersParams()
        params.event = changeEvent
        svc.didChangeWorkspaceFolders(params)
        assertFalse(folders.contains(existing))
    }

    // -------------------------------------------------------------------------
    // didChangeConfiguration()
    // -------------------------------------------------------------------------

    @Test
    fun `didChangeConfiguration is a no-op and does not throw`() {
        assertDoesNotThrow { buildService().didChangeConfiguration(DidChangeConfigurationParams()) }
    }

    // -------------------------------------------------------------------------
    // didChangeWatchedFiles()
    // -------------------------------------------------------------------------

    @Test
    fun `didChangeWatchedFiles posts DidChangeWatchedFilesEvent to the event bus`() {
        val bus = EventBus.builder().build()
        var received: DidChangeWatchedFilesEvent? = null
        val subscriber =
            object {
                @Subscribe
                fun on(e: DidChangeWatchedFilesEvent) {
                    received = e
                }
            }
        bus.register(subscriber)
        val svc = buildService(eventBus = bus)
        val params =
            DidChangeWatchedFilesParams(
                listOf(FileEvent("file:///a.cql", FileChangeType.Changed)),
            )
        svc.didChangeWatchedFiles(params)
        assertNotNull(received)
        assertEquals("file:///a.cql", received!!.params().changes[0].uri)
    }

    // -------------------------------------------------------------------------
    // executeCommand() / executeCommandFromContributions()
    // -------------------------------------------------------------------------

    @Test
    fun `executeCommand dispatches to the matching contribution`() {
        val svc = buildService(listOf(fakeContribution("cmd.a")))
        val result = svc.executeCommand(ExecuteCommandParams("cmd.a", emptyList())).join()
        assertEquals("cmd.a_result", result)
    }

    @Test
    fun `executeCommand for unknown command shows error on client and returns null`() {
        val mockClient = Mockito.mock(LanguageClient::class.java)
        val svc = buildService(client = mockClient)
        val result = svc.executeCommand(ExecuteCommandParams("cmd.unknown", emptyList())).join()
        assertNull(result)
        Mockito.verify(mockClient).showMessage(Mockito.any())
    }

    @Test
    fun `executeCommand wraps synchronous contribution exception into failed future`() {
        val mockClient = Mockito.mock(LanguageClient::class.java)
        val failContrib =
            object : CommandContribution {
                override fun getCommands() = setOf("cmd.fail")

                override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> = throw RuntimeException("boom")
            }
        val svc = buildService(listOf(failContrib), client = mockClient)
        val result = svc.executeCommand(ExecuteCommandParams("cmd.fail", emptyList()))
        assertTrue(result.isCompletedExceptionally)
        Mockito.verify(mockClient).showMessage(Mockito.any())
    }
}
