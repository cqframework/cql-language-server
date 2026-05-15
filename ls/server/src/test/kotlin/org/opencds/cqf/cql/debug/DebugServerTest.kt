package org.opencds.cqf.cql.debug

import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.EvaluateArguments
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

    /** Subclass with multiple snapshots including a re-assigned name to test lastOrNull semantics. */
    private class MultiSnapshotTestServer(
        cm: CqlCompilationManager,
        cs: ContentService,
        ig: IgContextManager,
        lrm: LibraryResolutionManager,
    ) : CqlDebugServer(cm, cs, ig, lrm) {
        override fun executeLaunch(args: DebugLaunchArgs) {
            snapshots = listOf(
                ExpressionSnapshot("A", "1", "file:///test.cql", 0, 0, 0, 1),
                ExpressionSnapshot("B", "false", "file:///test.cql", 1, 0, 1, 5),
                ExpressionSnapshot("A", "42", "file:///test.cql", 2, 0, 2, 2),
                ExpressionSnapshot("C", "\"hello\"", "file:///test.cql", 3, 0, 3, 7),
                ExpressionSnapshot("B", "true", "file:///test.cql", 4, 0, 4, 4),
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

    // -- evaluate / hover tests -------------------------------------------------------

    private fun makeMultiSnapshotServer(): CqlDebugServer {
        val cs = mock(ContentService::class.java)
        val cm = mock(CqlCompilationManager::class.java)
        val ig = mock(IgContextManager::class.java)
        val lrm = mock(LibraryResolutionManager::class.java)
        return MultiSnapshotTestServer(cm, cs, ig, lrm)
    }

    /** Advance the server to frame `targetIndex` by stepping from its current position. */
    private fun advanceToFrame(server: CqlDebugServer, client: IDebugProtocolClient, targetIndex: Int) {
        for (i in 0..targetIndex) {
            server.next(org.eclipse.lsp4j.debug.NextArguments()).get()
        }
    }

    @Test
    fun evaluate_hover_returnsMostRecentValueAtFrame() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeMultiSnapshotServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        // Paused at entry (frame 0) — step to frame 3 where A=42 and B=false are both visible
        advanceToFrame(server, client, 3)

        // Hover "A" should return "42" (the most recent A at frame 3), not "1"
        val evalArgs = EvaluateArguments().also {
            it.context = "hover"
            it.expression = "A"
            it.frameId = 3
        }
        val response = server.evaluate(evalArgs).get()
        assertEquals("42", response.result)
        assertEquals(0, response.variablesReference)
    }

    @Test
    fun evaluate_hover_unknownExpression_returnsNotAvailable() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeMultiSnapshotServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        advanceToFrame(server, client, 2)

        val evalArgs = EvaluateArguments().also {
            it.context = "hover"
            it.expression = "Unknown"
            it.frameId = 2
        }
        val response = server.evaluate(evalArgs).get()
        assertEquals("not available", response.result)
        assertEquals(0, response.variablesReference)
    }

    @Test
    fun evaluate_hover_nullFrameId_searchesAllSnapshots() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeMultiSnapshotServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        advanceToFrame(server, client, 1)

        // frameId is null — should search all snapshots and return the last "B" (true)
        val evalArgs = EvaluateArguments().also {
            it.context = "hover"
            it.expression = "B"
            it.frameId = null
        }
        val response = server.evaluate(evalArgs).get()
        assertEquals("true", response.result)
        assertEquals(0, response.variablesReference)
    }

    @Test
    fun evaluate_nonHoverContext_returnsN_A() {
        val server = makeMultiSnapshotServer()

        val evalArgs = EvaluateArguments().also {
            it.context = "watch"
            it.expression = "A"
            it.frameId = 0
        }
        val response = server.evaluate(evalArgs).get()
        assertEquals("N/A", response.result)
        assertEquals(0, response.variablesReference)
    }

    @Test
    fun initialize_supportsEvaluateForHovers_isTrue() {
        val server = makeServer()
        val capabilities = server.initialize(InitializeRequestArguments()).get()
        assertTrue(capabilities.supportsEvaluateForHovers)
    }
}
