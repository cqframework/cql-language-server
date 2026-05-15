package org.opencds.cqf.cql.debug

import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager

class DebugServerTest {
    private fun makeServer(): CqlDebugServer {
        val cs = mock(ContentService::class.java)
        val cm = mock(CqlCompilationManager::class.java)
        val ig = mock(IgContextManager::class.java)
        val lrm = mock(LibraryResolutionManager::class.java)
        return CqlDebugServer(cm, cs, ig, lrm)
    }

    /** Subclass that stubs executeLaunch to bypass real CQL evaluation. */
    private class TestCqlDebugServer(
        cm: CqlCompilationManager,
        cs: ContentService,
        ig: IgContextManager,
        lrm: LibraryResolutionManager,
    ) : CqlDebugServer(cm, cs, ig, lrm) {
        override fun executeLaunch(args: DebugLaunchArgs) {
            snapshots = listOf(
                ExpressionSnapshot("Test", "true", "file:///test.cql", 0, 0, 0, 10),
            )
            currentIndex = -1
            stepToNext("entry")
        }
    }

    private fun makeTestServer(): CqlDebugServer {
        val cs = mock(ContentService::class.java)
        val cm = mock(CqlCompilationManager::class.java)
        val ig = mock(IgContextManager::class.java)
        val lrm = mock(LibraryResolutionManager::class.java)
        return TestCqlDebugServer(cm, cs, ig, lrm)
    }

    @Test
    fun handshake() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeTestServer()
        server.connect(client)

        assertEquals(ServerState.STARTED, server.getState())

        val capabilities: Capabilities = server.initialize(InitializeRequestArguments()).get()
        assertNotNull(capabilities)

        Mockito.verify(client).initialized()
        assertEquals(ServerState.INITIALIZED, server.getState())

        server.configurationDone(ConfigurationDoneArguments()).get()
        assertEquals(ServerState.CONFIGURED, server.getState())

        server.launch(HashMap()).get()

        Mockito.verify(client).stopped(any())
        assertEquals(ServerState.RUNNING, server.getState())
    }

    @Test
    fun initialize_whenAlreadyInitialized_throwsIllegalState() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        assertEquals(ServerState.INITIALIZED, server.getState())

        assertThrows(IllegalStateException::class.java) {
            server.initialize(InitializeRequestArguments())
        }
    }

    @Test
    fun configurationDone_whenNotInitialized_throwsIllegalState() {
        val server = makeServer()
        assertThrows(IllegalStateException::class.java) {
            server.configurationDone(ConfigurationDoneArguments())
        }
    }

    /**
     * VS Code sends `launch` immediately after `configurationDone` without waiting for the
     * configurationDone response. This test simulates that race: `launch` is submitted
     * concurrently with `configurationDone` and must wait until configuration completes.
     */
    @Test
    fun launch_concurrentWithConfigurationDone_waitsAndSucceeds() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeTestServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()

        // Submit launch before configurationDone completes (simulating VS Code's behavior)
        val launchFuture = CompletableFuture.supplyAsync { server.launch(HashMap()).get() }
        server.configurationDone(null).get()

        launchFuture.get(5, TimeUnit.SECONDS)
        assertEquals(ServerState.RUNNING, server.getState())
    }

    @Test
    fun exited_completesAfterFullLifecycle() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeTestServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        // drive to termination
        val nextArgs = org.eclipse.lsp4j.debug.NextArguments()
        server.next(nextArgs).get()

        assertTrue(server.exited().isDone, "exited() future should be completed after full lifecycle")
    }

    @Test
    fun configurationDone_withNullArgs_transitionsToConfigured() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()

        // VS Code sends null for ConfigurationDoneArguments (it is optional per DAP spec)
        server.configurationDone(null).get()
        assertEquals(ServerState.CONFIGURED, server.getState())
    }

    @Test
    fun setExceptionBreakpoints_returnsEmptyResponse() {
        val server = makeServer()
        val response = server.setExceptionBreakpoints(SetExceptionBreakpointsArguments()).get()
        assertNotNull(response)
    }

    @Test
    fun disconnect_fromStartedState_completesWithoutThrowing() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeServer()
        server.connect(client)
        server.disconnect(DisconnectArguments()).get()
        assertEquals(ServerState.STOPPED, server.getState())
    }

    @Test
    fun stepIn_matchesStepOver() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeTestServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        Mockito.verify(client).stopped(any())

        val stepInArgs = org.eclipse.lsp4j.debug.StepInArguments()
        server.stepIn(stepInArgs).get()

        Mockito.verify(client).terminated(any())
        Mockito.verify(client).exited(any())
        assertEquals(ServerState.STOPPED, server.getState())
    }

    @Test
    fun stepOut_matchesStepOver() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeTestServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        Mockito.verify(client).stopped(any())

        val stepOutArgs = org.eclipse.lsp4j.debug.StepOutArguments()
        server.stepOut(stepOutArgs).get()

        Mockito.verify(client).terminated(any())
        Mockito.verify(client).exited(any())
        assertEquals(ServerState.STOPPED, server.getState())
    }

    @Test
    fun next_afterLastSnapshot_terminates() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeTestServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        // first step is the "entry" stop on the first expression
        Mockito.verify(client).stopped(any())

        // second step hits the end => terminates and exits
        val nextArgs = org.eclipse.lsp4j.debug.NextArguments()
        server.next(nextArgs).get()

        Mockito.verify(client).terminated(any())
        Mockito.verify(client).exited(any())
        assertEquals(ServerState.STOPPED, server.getState())
    }
}
