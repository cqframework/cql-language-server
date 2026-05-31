package org.opencds.cqf.cql.debug

import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.EvaluateArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

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
            snapshots =
                listOf(
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
            snapshots =
                listOf(
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

    /**
     * Advances past [targetIndex] so that frameId = targetIndex is valid for
     * a subsequent evaluate(hover) request. Calls next() (targetIndex + 1) times.
     * After this, currentIndex == targetIndex + 1.
     */
    private fun advancePastFrame(
        server: CqlDebugServer,
        client: IDebugProtocolClient,
        targetIndex: Int,
    ) {
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
        advancePastFrame(server, client, 3)

        // Hover "A" should return "42" (the most recent A at frame 3), not "1"
        val evalArgs =
            EvaluateArguments().also {
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

        advancePastFrame(server, client, 2)

        val evalArgs =
            EvaluateArguments().also {
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

        advancePastFrame(server, client, 1)

        // frameId is null — should search all snapshots and return the last "B" (true)
        val evalArgs =
            EvaluateArguments().also {
                it.context = "hover"
                it.expression = "B"
                it.frameId = null
            }
        val response = server.evaluate(evalArgs).get()
        assertEquals("true", response.result)
        assertEquals(0, response.variablesReference)
    }

    @Test
    fun evaluate_nonHover_withUnknownName_returnsNotAvailable() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeMultiSnapshotServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        advancePastFrame(server, client, 1)

        // any non-hover context with an unknown name should return "not available"
        val evalArgs =
            EvaluateArguments().also {
                it.context = "repl"
                it.expression = "Unknown"
                it.frameId = 1
            }
        val response = server.evaluate(evalArgs).get()
        assertEquals("not available", response.result)
        assertEquals(0, response.variablesReference)
    }

    @Test
    fun evaluate_nullContext_returnsValueByName() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeMultiSnapshotServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        advancePastFrame(server, client, 1)

        // VS Code may send null context for Copy Value
        val evalArgs =
            EvaluateArguments().also {
                it.context = null
                it.expression = "A"
                it.frameId = 1
            }
        val response = server.evaluate(evalArgs).get()
        assertEquals("1", response.result)
        assertEquals(0, response.variablesReference)
    }

    @Test
    fun evaluate_unknownContext_returnsValueIfNameMatches() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeMultiSnapshotServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        advancePastFrame(server, client, 3)

        // Should work for any arbitrary context string
        val evalArgs =
            EvaluateArguments().also {
                it.context = "variables"
                it.expression = "C"
                it.frameId = 3
            }
        val response = server.evaluate(evalArgs).get()
        assertEquals("\"hello\"", response.result)
        assertEquals(0, response.variablesReference)
    }

    @Test
    fun evaluate_clipboard_returnsCurrentValue() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeMultiSnapshotServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        advancePastFrame(server, client, 1)

        // frame 1 sees A=1, B=false — clipboard should return "false" for B
        val evalArgs =
            EvaluateArguments().also {
                it.context = "clipboard"
                it.expression = "B"
                it.frameId = 1
            }
        val response = server.evaluate(evalArgs).get()
        assertEquals("false", response.result)
        assertEquals(0, response.variablesReference)
    }

    @Test
    fun evaluate_watch_knownName_returnsValue() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeMultiSnapshotServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        advancePastFrame(server, client, 2)

        // frame 2 sees A=42, B=false — watch should return "42" for A
        val evalArgs =
            EvaluateArguments().also {
                it.context = "watch"
                it.expression = "A"
                it.frameId = 2
            }
        val response = server.evaluate(evalArgs).get()
        assertEquals("42", response.result)
        assertEquals(0, response.variablesReference)
    }

    @Test
    fun evaluate_watch_unknownName_returnsNotAvailable() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeMultiSnapshotServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        advancePastFrame(server, client, 1)

        val evalArgs =
            EvaluateArguments().also {
                it.context = "watch"
                it.expression = "Unknown"
                it.frameId = 1
            }
        val response = server.evaluate(evalArgs).get()
        assertEquals("not available", response.result)
        assertEquals(0, response.variablesReference)
    }

    @Test
    fun initialize_supportsEvaluateForHovers_isTrue() {
        val server = makeServer()
        // initialize() blocks on client.join() inside its whenCompleteAsync,
        // so connect() must run first or the future never completes.
        server.connect(Mockito.mock(IDebugProtocolClient::class.java))
        val capabilities = server.initialize(InitializeRequestArguments()).get()
        assertTrue(capabilities.supportsEvaluateForHovers)
    }

    // -- partial name matching (quoted multi-word CQL identifiers) --------------------

    /** Subclass with multi-word snapshot names like `"Initial Population"`. */
    private class MultiWordSnapshotServer(
        cm: CqlCompilationManager,
        cs: ContentService,
        ig: IgContextManager,
        lrm: LibraryResolutionManager,
    ) : CqlDebugServer(cm, cs, ig, lrm) {
        override fun executeLaunch(args: DebugLaunchArgs) {
            snapshots =
                listOf(
                    ExpressionSnapshot("Initial Population", "true", "file:///test.cql", 0, 0, 0, 19),
                    ExpressionSnapshot("SDE Sex", "\"M\"", "file:///test.cql", 2, 0, 2, 5),
                    ExpressionSnapshot("Test", "42", "file:///test.cql", 4, 0, 4, 4),
                )
            currentIndex = -1
            stepToNext("entry")
        }
    }

    private fun makeMultiWordSnapshotServer(): CqlDebugServer {
        val cs = mock(ContentService::class.java)
        val cm = mock(CqlCompilationManager::class.java)
        val ig = mock(IgContextManager::class.java)
        val lrm = mock(LibraryResolutionManager::class.java)
        return MultiWordSnapshotServer(cm, cs, ig, lrm)
    }

    @Test
    fun evaluate_hover_partialWordMatchesMultiWordName() {
        // CQL `define "Initial Population": ...` — snapshot name is "Initial Population"
        // VS Code sends just "Population" (default word extraction splits on spaces)
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeMultiWordSnapshotServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        advancePastFrame(server, client, 2)

        val evalArgs =
            EvaluateArguments().also {
                it.context = "hover"
                it.expression = "Population"
                it.frameId = 2
            }
        val response = server.evaluate(evalArgs).get()
        assertEquals("true", response.result)
        assertEquals(0, response.variablesReference)
    }

    @Test
    fun evaluate_hover_quotedExpression_stripsQuotesBeforeMatch() {
        // User hovers over `"SDE Sex"` in the editor; VS Code may send `"SDE Sex"` (with quotes)
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeMultiWordSnapshotServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        advancePastFrame(server, client, 2)

        val evalArgs =
            EvaluateArguments().also {
                it.context = "hover"
                it.expression = "\"SDE Sex\""
                it.frameId = 2
            }
        val response = server.evaluate(evalArgs).get()
        assertEquals("\"M\"", response.result)
        assertEquals(0, response.variablesReference)
    }

    @Test
    fun evaluate_hover_unknownExpressionAfterPartialMatch_returnsNotAvailable() {
        // Expression that is not a snapshot name or a word within any snapshot name
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeMultiWordSnapshotServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        advancePastFrame(server, client, 2)

        val evalArgs =
            EvaluateArguments().also {
                it.context = "hover"
                it.expression = "Foo"
                it.frameId = 2
            }
        val response = server.evaluate(evalArgs).get()
        assertEquals("not available", response.result)
        assertEquals(0, response.variablesReference)
    }

    @Test
    fun evaluate_hover_exactMatch_stillWorksAfterPartialMatchChange() {
        // Exact match should still take priority over partial word matching
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeMultiSnapshotServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        advancePastFrame(server, client, 4)

        val evalArgs =
            EvaluateArguments().also {
                it.context = "hover"
                it.expression = "A"
                it.frameId = 4
            }
        val response = server.evaluate(evalArgs).get()
        assertEquals("42", response.result)
    }

    // -- position-based sub-expression hover ------------------------------------------

    /** Subclass that pre-populates both snapshots and subExpressionSnapshots. */
    private class SubExpressionTestServer(
        cm: CqlCompilationManager,
        cs: ContentService,
        ig: IgContextManager,
        lrm: LibraryResolutionManager,
    ) : CqlDebugServer(cm, cs, ig, lrm) {
        override fun executeLaunch(args: DebugLaunchArgs) {
            snapshots =
                listOf(
                    ExpressionSnapshot("isOfficial", "true", "file:///test.cql", 8, 0, 9, 55),
                    ExpressionSnapshot("Patient Name", "\"John\"", "file:///test.cql", 11, 0, 18, 5),
                    ExpressionSnapshot("Extra", "42", "file:///test.cql", 20, 0, 20, 5),
                )
            // Simulate sub-expression trace results with locators in 1-indexed TrackBack format.
            // Line 9 CQL:   exists (Patient.name Name where Name.use.value = 'official')
            //             line 9, col 10-21 is "Patient.name"
            //             line 9, col 13-21 is "Patient.name" (parsed differently by CQL engine, Property vs Query)
            //             line 9, col 22-26 is "Name"
            subExpressionSnapshots =
                listOf(
                    SubExpressionSnapshot("true", "isOfficial", 9, 10, 9, 21), // Patient.name
                    SubExpressionSnapshot("\"John\"", "isOfficial", 9, 13, 9, 20), // name (nested)
                    SubExpressionSnapshot("FHIR.HumanName {\$this: ...}", "isOfficial", 9, 22, 9, 26), // Name alias
                )
            currentIndex = -1
            stepToNext("entry")
        }
    }

    private fun makeSubExpressionTestServer(): CqlDebugServer {
        val cs = mock(ContentService::class.java)
        val cm = mock(CqlCompilationManager::class.java)
        val ig = mock(IgContextManager::class.java)
        val lrm = mock(LibraryResolutionManager::class.java)
        return SubExpressionTestServer(cm, cs, ig, lrm)
    }

    @Test
    fun evaluate_hover_positionEncoded_matchesSubExpression() {
        // Cursor at line 9, col 11 (start of "Patient.name", before the nested "name" sub-expression)
        // → matches Patient.name sub-expression (9:10-9:21), not name (9:13-9:20)
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeSubExpressionTestServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        advancePastFrame(server, client, 0)

        val evalArgs =
            EvaluateArguments().also {
                it.context = "hover"
                it.expression = "@9:11"
                it.frameId = 0
            }
        val response = server.evaluate(evalArgs).get()
        assertEquals("true", response.result)
        assertEquals(0, response.variablesReference)
    }

    @Test
    fun evaluate_hover_positionEncoded_outsideAnySubExpression_returnsNotAvailable() {
        // Cursor at line 8, col 0 — outside the sub-expression ranges
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeSubExpressionTestServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        advancePastFrame(server, client, 0)

        val evalArgs =
            EvaluateArguments().also {
                it.context = "hover"
                it.expression = "@8:0"
                it.frameId = 0
            }
        val response = server.evaluate(evalArgs).get()
        assertEquals("not available", response.result)
        assertEquals(0, response.variablesReference)
    }

    @Test
    fun evaluate_hover_positionEncoded_multipleNested_returnsTightest() {
        // Cursor at line 9, col 16 — inside both Patient.name (9:10-9:21) and name (9:13-9:20)
        // Tightest span is name (9:13-9:20)
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeSubExpressionTestServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        advancePastFrame(server, client, 0)

        val evalArgs =
            EvaluateArguments().also {
                it.context = "hover"
                it.expression = "@9:16"
                it.frameId = 0
            }
        val response = server.evaluate(evalArgs).get()
        assertEquals("\"John\"", response.result)
        assertEquals(0, response.variablesReference)
    }

    @Test
    fun evaluate_hover_nonPositionExpression_stillMatchesDefineName() {
        // Non-position expression still uses nameMatches for define names
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeSubExpressionTestServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        advancePastFrame(server, client, 0)

        val evalArgs =
            EvaluateArguments().also {
                it.context = "hover"
                it.expression = "isOfficial"
                it.frameId = 0
            }
        val response = server.evaluate(evalArgs).get()
        assertEquals("true", response.result)
        assertEquals(0, response.variablesReference)
    }

    @Test
    fun evaluate_hover_positionEncoded_wrongFrame_returnsNotAvailable() {
        // Frame 1 ("Patient Name" define) has no sub-expression snapshots, so position
        // at 9:15 should not match.
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeSubExpressionTestServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        advancePastFrame(server, client, 1)

        val evalArgs =
            EvaluateArguments().also {
                it.context = "hover"
                it.expression = "@9:15"
                it.frameId = 1
            }
        val response = server.evaluate(evalArgs).get()
        assertEquals("not available", response.result)
    }

    @Test
    fun evaluate_hover_positionEncoded_invalidFormat_returnsNotAvailable() {
        // Malformed @-expression: parseHoverPosition returns null → notAvailable()
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = makeSubExpressionTestServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        advancePastFrame(server, client, 0)

        val evalArgs =
            EvaluateArguments().also {
                it.context = "hover"
                it.expression = "@notaposition"
                it.frameId = 0
            }
        val response = server.evaluate(evalArgs).get()
        assertEquals("not available", response.result)
    }

    // -- SubExpressionSnapshot boundary tests -----------------------------------------

    @Test
    fun subExpressionSnapshot_contains_startOfRange() {
        val snap = SubExpressionSnapshot("v", "p", 9, 10, 9, 21)
        assertTrue(snap.contains(9, 10))
    }

    @Test
    fun subExpressionSnapshot_contains_endOfRange() {
        val snap = SubExpressionSnapshot("v", "p", 9, 10, 9, 21)
        // endChar stores the raw TrackBack column (no -1 applied); contains() uses
        // col > endChar so endChar itself is inclusive — cursor just past the last
        // char still matches the expression.
        assertTrue(snap.contains(9, 21))
    }

    @Test
    fun subExpressionSnapshot_contains_justBeforeStart() {
        val snap = SubExpressionSnapshot("v", "p", 9, 10, 9, 21)
        assertFalse(snap.contains(9, 9))
    }

    @Test
    fun subExpressionSnapshot_contains_justAfterEnd() {
        val snap = SubExpressionSnapshot("v", "p", 9, 10, 9, 21)
        assertFalse(snap.contains(9, 22))
    }

    @Test
    fun subExpressionSnapshot_contains_aboveRange() {
        val snap = SubExpressionSnapshot("v", "p", 9, 10, 9, 21)
        assertFalse(snap.contains(8, 15))
    }

    @Test
    fun subExpressionSnapshot_contains_belowRange() {
        val snap = SubExpressionSnapshot("v", "p", 9, 10, 9, 21)
        assertFalse(snap.contains(10, 0))
    }

    // -- dependency-first stepping order ----------------------------------------------

    /**
     * Simulates a CQL library where "PatientAge" is a dependency of "InitPop".
     * Source order: InitPop at line 1, PatientAge at line 5.
     * Dependency-first order (from trace): PatientAge, then InitPop.
     */
    private class DependencyOrderTestServer(
        cm: CqlCompilationManager,
        cs: ContentService,
        ig: IgContextManager,
        lrm: LibraryResolutionManager,
    ) : CqlDebugServer(cm, cs, ig, lrm) {
        override fun executeLaunch(args: DebugLaunchArgs) {
            val defineOrder = listOf("PatientAge", "InitPop")
            val orderIndex = defineOrder.withIndex().associate { (i, name) -> name to i }
            snapshots =
                listOf(
                    ExpressionSnapshot("InitPop", "true", "file:///test.cql", 1, 0, 1, 10),
                    ExpressionSnapshot("PatientAge", "true", "file:///test.cql", 5, 0, 5, 20),
                ).sortedBy { orderIndex[it.name] ?: Int.MAX_VALUE }
            subExpressionSnapshots = emptyList()
            currentIndex = -1
            stepToNext("entry")
        }
    }

    @Test
    fun snapshots_sortedByDependencyFirstOrder_notSourceOrder() {
        // "PatientAge" (line 5) is a dependency of "InitPop" (line 1).
        // Source-order sort would visit InitPop first; dependency-first must visit PatientAge first.
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val cs = mock(ContentService::class.java)
        val cm = mock(CqlCompilationManager::class.java)
        val ig = mock(IgContextManager::class.java)
        val lrm = mock(LibraryResolutionManager::class.java)
        val server = DependencyOrderTestServer(cm, cs, ig, lrm)
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        // Stopped at entry — first frame must be the dependency, not the source-order first define
        val stackArgs = org.eclipse.lsp4j.debug.StackTraceArguments().also { it.threadId = 1 }
        val frames1 = server.stackTrace(stackArgs).get()
        assertEquals("PatientAge", frames1.stackFrames[0].name)

        // Step once — now at the caller
        server.next(org.eclipse.lsp4j.debug.NextArguments()).get()
        val frames2 = server.stackTrace(stackArgs).get()
        assertEquals("InitPop", frames2.stackFrames[0].name)
    }
}
