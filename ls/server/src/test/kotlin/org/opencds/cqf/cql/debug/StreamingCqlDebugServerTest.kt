package org.opencds.cqf.cql.debug

import ca.uhn.fhir.context.FhirContext
import org.eclipse.lsp4j.debug.ContinueArguments
import org.eclipse.lsp4j.debug.EvaluateArguments
import org.eclipse.lsp4j.debug.NextArguments
import org.eclipse.lsp4j.debug.ScopesArguments
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.StackTraceArguments
import org.eclipse.lsp4j.debug.VariablesArguments
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.ExpressionRef
import org.hl7.elm.r1.FunctionRef
import org.hl7.elm.r1.Library
import org.hl7.elm.r1.Literal
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.fhir.instance.model.api.IBase
import org.hl7.fhir.r4.model.Meta
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Encounter
import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.opencds.cqf.cql.engine.execution.Environment
import org.opencds.cqf.cql.engine.execution.State
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import java.nio.file.Files
import java.nio.file.Path

class StreamingCqlDebugServerTest {
    /**
     * Subclass that pre-sets [streamingHandler] to a [testHandler] and stubs
     * [executeLaunchStreaming] so that [launch] is safe to call (though most tests
     * bypass launch and manipulate the handler directly).
     */
    private class TestStreamingServer(
        cm: CqlCompilationManager,
        cs: ContentService,
        ig: IgContextManager,
        lrm: LibraryResolutionManager,
    ) : CqlDebugServer(cm, cs, ig, lrm) {
        val testHandler = StreamingBreakpointHandler()

        init {
            streamingHandler = testHandler
        }

        override fun executeLaunchStreaming(args: DebugLaunchArgs) {
            // no-op for tests
        }

        fun setLaunchUri(uri: String) {
            streamingLaunchUri = uri
        }

        fun initParameterMetadata(metadata: Map<String, List<ParameterMetadata>>) {
            parameterMetadata = metadata
        }

        fun initSnapshots(snapshots: List<ExpressionSnapshot>) {
            this.snapshots = snapshots
        }

        fun initCurrentIndex(index: Int) {
            currentIndex = index
        }

        fun initLaunchArgs(args: DebugLaunchArgs) {
            launchArgs = args
        }

        fun triggerCompletion(error: Throwable?) {
            val future = java.util.concurrent.CompletableFuture<Unit>()
            this.streamingCompletion = future

            future.whenComplete { _, err ->
                if (err != null) {
                    val terminateServerMethod = CqlDebugServer::class.java.getDeclaredMethod("terminateServer")
                    terminateServerMethod.isAccessible = true
                    terminateServerMethod.invoke(this)

                    val exitServerMethod = CqlDebugServer::class.java.getDeclaredMethod("exitServer", Int::class.java)
                    exitServerMethod.isAccessible = true
                    exitServerMethod.invoke(this, 1)
                } else {
                    val terminateServerMethod = CqlDebugServer::class.java.getDeclaredMethod("terminateServer")
                    terminateServerMethod.isAccessible = true
                    terminateServerMethod.invoke(this)

                    val exitServerMethod = CqlDebugServer::class.java.getDeclaredMethod("exitServer", Int::class.java)
                    exitServerMethod.isAccessible = true
                    exitServerMethod.invoke(this, 0)
                }
            }

            if (error != null) {
                future.completeExceptionally(error)
            } else {
                future.complete(Unit)
            }
        }
    }

    /** Create a server whose [streamingHandler] points to its [TestStreamingServer.testHandler]. */
    private fun makeServer(): TestStreamingServer {
        val cs = mock(ContentService::class.java)
        val cm = mock(CqlCompilationManager::class.java)
        val ig = mock(IgContextManager::class.java)
        val lrm = mock(LibraryResolutionManager::class.java)
        return TestStreamingServer(cm, cs, ig, lrm)
    }

    /** Convenience: server + connected mock client. */
    private fun setupServer(): TestStreamingServer {
        val client = Mockito.mock(org.eclipse.lsp4j.debug.services.IDebugProtocolClient::class.java)
        val server = makeServer()
        server.connect(client)
        return server
    }

    private fun pauseAt(
        handler: StreamingBreakpointHandler,
        line: Int,
        char: Int,
        endLine: Int,
        endChar: Int,
        name: String,
    ) {
        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = name
                it.locator = "$line:$char-$endLine:$endChar"
            }
        val state = State(Environment(null))
        handler.onBeforeExpression(elm, state)
    }

    // -- stackTrace ---------------------------------------------------------

    @Test
    fun `stackTrace returns frame info from paused handler`() {
        val server = setupServer()
        server.setLaunchUri("file:///test.cql")
        pauseAt(server.testHandler, 10, 5, 10, 25, "TestExpression")

        val response = server.stackTrace(StackTraceArguments().also { it.threadId = 1 }).get()

        assertEquals(1, response.totalFrames)
        val frame = response.stackFrames[0]
        assertEquals("TestExpression", frame.name)
        assertEquals(10, frame.line) // TrackBack "10" → 0-indexed 9 → DAP 10
        assertEquals(5, frame.column) // TrackBack "5" → 0-indexed 4 → DAP 5
        assertEquals(10, frame.endLine)
        assertEquals(26, frame.endColumn) // +1 because TrackBack end is inclusive but DAP endColumn is exclusive
        assertNotNull(frame.source)
        assertEquals("/test.cql", frame.source.path)
    }

    @Test
    fun `stackTrace handles missing locator`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm = Literal().also { it.locator = null }
        val state = State(Environment(null))
        handler.onBeforeExpression(elm, state)

        val response = server.stackTrace(StackTraceArguments().also { it.threadId = 1 }).get()
        assertEquals(1, response.totalFrames)
        assertEquals(1, response.stackFrames[0].line) // default 0 → DAP line 1
    }

    @Test
    fun `stackTrace single define returns 1 frame`() {
        val server = setupServer()
        server.setLaunchUri("file:///test.cql")
        val handler = server.testHandler

        val def = ExpressionDef().also { it.name = "Initial Population"; it.locator = "5:1-5:30" }
        val state = State(Environment(null))
        handler.onExpressionDefEntered(def, null, state)

        pauseAt(handler, 5, 1, 5, 30, "Initial Population")

        val response = server.stackTrace(StackTraceArguments().also { it.threadId = 1 }).get()
        assertEquals(1, response.totalFrames)
        assertEquals("Initial Population", response.stackFrames[0].name)
        assertEquals(5, response.stackFrames[0].line)
    }

    @Test
    fun `stackTrace nested defines returns 2 frames`() {
        val server = setupServer()
        server.setLaunchUri("file:///test.cql")
        val handler = server.testHandler

        val outerDef = ExpressionDef().also { it.name = "Outer"; it.locator = "1:1-1:30" }
        val innerDef = ExpressionDef().also { it.name = "Inner"; it.locator = "2:1-2:30" }
        val ref = ExpressionRef().also { it.name = "Inner"; it.locator = "1:15-1:20" }
        val state = State(Environment(null))

        handler.onExpressionDefEntered(outerDef, null, state)
        handler.onExpressionDefEntered(innerDef, ref, state)

        handler.stepIn()
        val pausedElm = ExpressionDef().also { it.name = "Inner"; it.locator = "2:5-2:20" }
        handler.onBeforeExpression(pausedElm, state)

        val response = server.stackTrace(StackTraceArguments().also { it.threadId = 1 }).get()
        assertEquals(2, response.totalFrames)

        // Frame 0 = innermost (Inner)
        assertEquals("Inner", response.stackFrames[0].name)
        assertEquals(2, response.stackFrames[0].line)
        assertEquals(5, response.stackFrames[0].column)

        // Frame 1 = caller (Outer), position at call site
        assertEquals("Outer", response.stackFrames[1].name)
        assertEquals(1, response.stackFrames[1].line)  // call site line from expression ref
        assertEquals(15, response.stackFrames[1].column)  // call site col from expression ref
    }

    @Test
    fun `stackTrace function call frame`() {
        val server = setupServer()
        server.setLaunchUri("file:///test.cql")
        val handler = server.testHandler

        val funcDef = ExpressionDef().also { it.name = "MyFunc"; it.locator = "3:1-3:30" }
        val funcRef = FunctionRef().also { it.name = "MyFunc"; it.locator = "1:20-1:25" }
        val state = State(Environment(null))

        handler.onExpressionDefEntered(funcDef, funcRef, state)

        handler.stepIn()
        val pausedElm = Literal().also { it.locator = "3:10-3:15" }
        handler.onBeforeExpression(pausedElm, state)

        val response = server.stackTrace(StackTraceArguments().also { it.threadId = 1 }).get()
        assertEquals(1, response.totalFrames)
        assertEquals("MyFunc", response.stackFrames[0].name)
    }

    @Test
    fun `stackTrace totalFrames matches stackFrames length`() {
        val server = setupServer()
        server.setLaunchUri("file:///test.cql")
        val handler = server.testHandler

        val defA = ExpressionDef().also { it.name = "A"; it.locator = "1:1-1:30" }
        val defB = ExpressionDef().also { it.name = "B"; it.locator = "2:1-2:30" }
        val defC = ExpressionDef().also { it.name = "C"; it.locator = "3:1-3:30" }
        val refB = ExpressionRef().also { it.name = "B"; it.locator = "1:10-1:11" }
        val refC = ExpressionRef().also { it.name = "C"; it.locator = "2:10-2:11" }
        val state = State(Environment(null))

        handler.onExpressionDefEntered(defA, null, state)
        handler.onExpressionDefEntered(defB, refB, state)
        handler.onExpressionDefEntered(defC, refC, state)

        handler.stepIn()
        val pausedElm = Literal().also { it.locator = "3:10-3:15" }
        handler.onBeforeExpression(pausedElm, state)

        val response = server.stackTrace(StackTraceArguments().also { it.threadId = 1 }).get()
        assertEquals(3, response.totalFrames)
        assertEquals(response.totalFrames, response.stackFrames.size)
    }

    // -- scopes -------------------------------------------------------------

    @Test
    fun `scopes returns Locals scope in streaming mode`() {
        val server = setupServer()
        val response = server.scopes(ScopesArguments().also { it.frameId = 0 }).get()
        assertEquals(2, response.scopes.size)
        assertEquals("Locals", response.scopes[0].name)
        assertEquals(1, response.scopes[0].variablesReference)
        assertEquals("Resolved Defines", response.scopes[1].name)
        assertEquals(3, response.scopes[1].variablesReference)
    }

    // -- variables -----------------------------------------------------------

    @Test
    fun `variables returns empty list when state is null`() {
        val server = setupServer()
        val response = server.variables(VariablesArguments().also { it.variablesReference = 1 }).get()
        assertNotNull(response.variables)
        assertEquals(0, response.variables.size)
    }

    @Test
    fun `variables formats values as JSON`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))
        state.stack.addFirst(State.ActivationFrame(null, null, null, 0L))
        // Add a variable without a value (the engine Value API doesn't support maps directly)
        state.topActivationFrame.variables.addFirst(
            org.opencds.cqf.cql.engine.execution.Variable("patientId"),
        )
        handler.onBeforeExpression(elm, state)
        // Inject the map value into the registry after onBeforeExpression clears stack vars
        handler.runtimeRegistry.putStackVariable("patientId", mapOf("id" to "1111", "name" to listOf(mapOf("family" to "Chalmers"))), null)

        val response = server.variables(VariablesArguments().also { it.variablesReference = 1 }).get()
        assertNotNull(response.variables)
        assertEquals(1, response.variables.size)
        assertEquals("patientId", response.variables[0].name)
        assertEquals("{\"id\":\"1111\",\"name\":[{\"family\":\"Chalmers\"}]}", response.variables[0].value)
    }

    // -- evaluate -----------------------------------------------------------

    @Test
    fun `evaluate returns notAvailable when state is null`() {
        val server = setupServer()
        val response =
            server.evaluate(
                EvaluateArguments().also {
                    it.expression = "TestExpr"
                    it.context = "hover"
                    it.frameId = 0
                },
            ).get()
        assertEquals("not available", response.result)
    }

    @Test
    fun `evaluate finds stack variable by name`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))
        state.stack.addFirst(State.ActivationFrame(null, null, null, 0L))
        state.topActivationFrame.variables.addFirst(
            org.opencds.cqf.cql.engine.execution.Variable("patientId"),
        )
        handler.onBeforeExpression(elm, state)
        handler.runtimeRegistry.putStackVariable("patientId", mapOf("id" to "1111"), null)

        val response =
            server.evaluate(
                EvaluateArguments().also {
                    it.expression = "patientId"
                    it.context = "hover"
                    it.frameId = 0
                },
            ).get()
        assertEquals("{\"id\":\"1111\"}", response.result)
    }

    @Test
    fun `evaluate finds context value by name`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))
        state.setContextValue("Patient", "pat-1")
        handler.contextResourcesByName["Patient"] = mapOf("id" to "pat-1", "name" to listOf(mapOf("family" to "Smith")))
        handler.onBeforeExpression(elm, state)

        val response =
            server.evaluate(
                EvaluateArguments().also {
                    it.expression = "Patient"
                    it.context = "hover"
                    it.frameId = 0
                },
            ).get()
        assertEquals("{\"id\":\"pat-1\",\"name\":[{\"family\":\"Smith\"}]}", response.result)
    }

    @Test
    fun `evaluate finds cached define by name`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))
        // Populate the evaluation cache with a define result
        val libId = org.hl7.elm.r1.VersionedIdentifier().also { it.id = "TestLib" }
        state.cache.cacheExpression(libId, "isOfficial", org.opencds.cqf.cql.engine.execution.ExpressionResult(true, null))
        handler.onBeforeExpression(elm, state)

        val response =
            server.evaluate(
                EvaluateArguments().also {
                    it.expression = "isOfficial"
                    it.context = "hover"
                    it.frameId = 0
                },
            ).get()
        assertEquals("true", response.result)
    }

    @Test
    fun `evaluate returns notAvailable for unknown expression`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))
        state.stack.addFirst(State.ActivationFrame(null, null, null, 0L))
        state.topActivationFrame.variables.addFirst(
            org.opencds.cqf.cql.engine.execution.Variable("patientId").withValue("1111"),
        )
        handler.onBeforeExpression(elm, state)

        val response =
            server.evaluate(
                EvaluateArguments().also {
                    it.expression = "nonExistent"
                    it.context = "hover"
                    it.frameId = 0
                },
            ).get()
        assertEquals("not available", response.result)
    }

    // -- setBreakpoints -----------------------------------------------------

    @Test
    fun `setBreakpoints updates streaming handler`() {
        val server = setupServer()
        val bpArgs =
            SetBreakpointsArguments().also {
                it.source = Source().also { s -> s.path = "/test.cql" }
                it.breakpoints =
                    arrayOf(
                        org.eclipse.lsp4j.debug.SourceBreakpoint().also { bp -> bp.line = 5 },
                        org.eclipse.lsp4j.debug.SourceBreakpoint().also { bp -> bp.line = 10 },
                    )
            }
        val response = server.setBreakpoints(bpArgs).get()
        assertEquals(2, response.breakpoints.size)
        assertEquals(setOf(5, 10), server.testHandler.getBreakpointLines())
    }

    // -- next dispatches to handler -----------------------------------------

    @Test
    fun `next dispatches to handler stepOver`() {
        val server = setupServer()
        val handler = server.testHandler

        pauseAt(handler, 1, 1, 1, 10, "A")
        assertEquals(StreamingBreakpointHandler.StepMode.STEP_IN, handler.getStepMode())

        server.next(NextArguments()).get()
        assertEquals(StreamingBreakpointHandler.StepMode.STEP_OVER, handler.getStepMode())
    }

    // -- stepIn ------------------------------------------------------------

    @Test
    fun `stepIn dispatches to handler`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.resume()
        assertEquals(StreamingBreakpointHandler.StepMode.CONTINUE, handler.getStepMode())

        server.stepIn(org.eclipse.lsp4j.debug.StepInArguments()).get()
        assertEquals(StreamingBreakpointHandler.StepMode.STEP_IN, handler.getStepMode())
    }

    // -- stepOut -----------------------------------------------------------

    @Test
    fun `stepOut dispatches to handler`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.resume()
        server.stepOut(org.eclipse.lsp4j.debug.StepOutArguments()).get()
        assertEquals(StreamingBreakpointHandler.StepMode.STEP_OUT, handler.getStepMode())
    }

    // -- continue_ ----------------------------------------------------------

    @Test
    fun `continue_ dispatches to handler`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        server.continue_(ContinueArguments()).get()
        assertEquals(StreamingBreakpointHandler.StepMode.CONTINUE, handler.getStepMode())
    }

    // -- extractExpressionName helper ---------------------------------------

    @Test
    fun `stackTrace shows ExpressionDef name`() {
        val server = setupServer()
        pauseAt(server.testHandler, 3, 1, 3, 20, "Initial Population")

        val response = server.stackTrace(StackTraceArguments().also { it.threadId = 1 }).get()
        assertEquals("Initial Population", response.stackFrames[0].name)
    }

    @Test
    fun `stackTrace shows class name for non-ExpressionDef elements`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm = Literal().also { it.locator = "3:1-3:5" }
        val state = State(Environment(null))
        handler.onBeforeExpression(elm, state)

        val response = server.stackTrace(StackTraceArguments().also { it.threadId = 1 }).get()
        assertEquals("Literal", response.stackFrames[0].name)
    }

    @Test
    fun `variables shows full FHIR Patient resource`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))

        // Setup Patient resource
        val patient = Patient()
        patient.id = "pat-1"
        patient.addName().setFamily("Smith").addGiven("John")

        // This is where engine stores the ID as string
        state.setContextValue("Patient", "pat-1")

        // This is where we now store the full resource
        handler.contextResourcesByName["Patient"] = patient

        handler.onBeforeExpression(elm, state)

        val response = server.variables(VariablesArguments().also { it.variablesReference = 1 }).get()
        assertNotNull(response.variables)

        // Find Patient variable
        val patientVar = response.variables.firstOrNull { it.name == "Patient" }
        assertNotNull(patientVar)

        // Check for full resource JSON
        val value = patientVar!!.value
        assert(value.contains("Patient"))
        assert(value.contains("pat-1"))
        assert(value.contains("Smith"))
    }

    @Test
    fun `evaluate shows full FHIR Patient resource`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))

        // Setup Patient resource
        val patient = Patient()
        patient.id = "pat-1"
        patient.addName().setFamily("Smith")

        state.setContextValue("Patient", "pat-1")
        handler.contextResourcesByName["Patient"] = patient

        handler.onBeforeExpression(elm, state)

        val response =
            server.evaluate(
                EvaluateArguments().also {
                    it.expression = "Patient"
                    it.context = "hover"
                    it.frameId = 0
                },
            ).get()

        assertNotNull(response.result)
        val value = response.result
        assert(value.contains("Patient"))
        assert(value.contains("pat-1"))
        assert(value.contains("Smith"))
    }

    @Test
    fun `falls back to ID if handler doesn't have resource`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))

        state.setContextValue("Patient", "pat-1")
        // handler.contextResourcesByName NOT populated

        handler.onBeforeExpression(elm, state)

        val response =
            server.evaluate(
                EvaluateArguments().also {
                    it.expression = "Patient"
                    it.context = "hover"
                    it.frameId = 0
                },
            ).get()

        assertEquals("\"pat-1\"", response.result)
    }

    // -- parameters in scopes and variables ---------------------------------

    @Test
    fun `scopes returns Parameters scope when parameterMetadata is populated`() {
        val server = setupServer()
        server.initParameterMetadata(
            mapOf(
                "TestLibrary" to
                    listOf(
                        CqlDebugServer.ParameterMetadata(
                            name = "Measurement Period",
                            type = "Interval<DateTime>",
                            defaultValue = "Interval[2026-01-01, 2027-01-01]",
                        ),
                    ),
            ),
        )

        val response = server.scopes(ScopesArguments().also { it.frameId = 0 }).get()
        assertEquals(3, response.scopes.size)
        val paramScope = response.scopes.firstOrNull { it.name == "Parameters" }
        assertNotNull(paramScope)
        assertEquals(2, paramScope!!.variablesReference)
        assertNotNull(response.scopes.firstOrNull { it.name == "Resolved Defines" })
    }

    @Test
    fun `scopes returns both Parameters and Locals when parameterMetadata populated in streaming`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))
        handler.onBeforeExpression(elm, state)

        server.initParameterMetadata(
            mapOf(
                "TestLibrary" to
                    listOf(
                        CqlDebugServer.ParameterMetadata(
                            name = "Measurement Period",
                            type = "Interval<DateTime>",
                            defaultValue = "Interval[2026-01-01, 2027-01-01]",
                        ),
                    ),
            ),
        )

        val response = server.scopes(ScopesArguments().also { it.frameId = 0 }).get()
        assertEquals(3, response.scopes.size)
        assertNotNull(response.scopes.firstOrNull { it.name == "Parameters" })
        assertNotNull(response.scopes.firstOrNull { it.name == "Locals" })
        assertNotNull(response.scopes.firstOrNull { it.name == "Resolved Defines" })
    }

    @Test
    fun `scopes does not return Parameters when no parameters exist`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))
        handler.onBeforeExpression(elm, state)

        // parameterMetadata is empty by default
        val response = server.scopes(ScopesArguments().also { it.frameId = 0 }).get()
        assertEquals(2, response.scopes.size)
        assertEquals("Locals", response.scopes[0].name)
        assertEquals(1, response.scopes[0].variablesReference)
        assertEquals("Resolved Defines", response.scopes[1].name)
        assertEquals(3, response.scopes[1].variablesReference)
        assertEquals(null, response.scopes.firstOrNull { it.name == "Parameters" })
    }

    @Test
    fun `variables returns parameter values in streaming mode when variablesReference is 2`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))

        // Add parameters to state - no library prefix means "(Global)"
        state.setParameter(null, "Measurement Period", "Interval[@2026-01-01, @2027-01-01)")
        state.setParameter(null, "Patient Type", "HMO")

        handler.onBeforeExpression(elm, state)

        // Load parameters into registry (mimics server's onPauseCallback)
        val paramTypes = mapOf("(Global)" to mapOf("Measurement Period" to "Interval<DateTime>", "Patient Type" to "String"))
        handler.runtimeRegistry.loadParameters(state, paramTypes)

        // variables(2) should return library groups
        val groupsResponse = server.variables(VariablesArguments().also { it.variablesReference = 2 }).get()
        assertEquals(1, groupsResponse.variables.size)

        val globalGroup = groupsResponse.variables.firstOrNull { it.name == "(Global)" }
        assertNotNull(globalGroup)
        assertEquals("2 parameter(s)", globalGroup!!.value)
        assertTrue(globalGroup.variablesReference > 0, "Library group ref must be positive for DAP expandability")

        // Expanding the library group with its ref should return individual parameters
        val paramsResponse = server.variables(VariablesArguments().also { it.variablesReference = globalGroup.variablesReference }).get()
        assertEquals(2, paramsResponse.variables.size)

        val measurementPeriod = paramsResponse.variables.firstOrNull { it.name == "Measurement Period" }
        assertNotNull(measurementPeriod)
        assertEquals("\"Interval[@2026-01-01, @2027-01-01)\"", measurementPeriod!!.value)
        assertEquals("Interval<DateTime>", measurementPeriod.type)

        val patientType = paramsResponse.variables.firstOrNull { it.name == "Patient Type" }
        assertNotNull(patientType)
        assertEquals("\"HMO\"", patientType!!.value)
        assertEquals("String", patientType.type)
    }

    @Test
    fun `variables returns parameter metadata with defaults in non-streaming mode`() {
        val server = setupServer()

        // Metadata grouped by library - using TestLibrary as the library name
        server.initParameterMetadata(
            mapOf(
                "TestLibrary" to
                    listOf(
                        CqlDebugServer.ParameterMetadata(
                            name = "Measurement Period",
                            type = "Interval<DateTime>",
                            defaultValue = "Interval[2026-01-01, 2027-01-01]",
                        ),
                        CqlDebugServer.ParameterMetadata(
                            name = "Rate",
                            type = "Decimal",
                            defaultValue = "2.5",
                        ),
                    ),
            ),
        )

        // variables(2) should return library groups
        val groupsResponse = server.variables(VariablesArguments().also { it.variablesReference = 2 }).get()
        assertEquals(1, groupsResponse.variables.size)

        val testLibraryGroup = groupsResponse.variables.firstOrNull { it.name == "TestLibrary" }
        assertNotNull(testLibraryGroup)
        assertEquals("2 parameter(s)", testLibraryGroup!!.value)
        assertTrue(testLibraryGroup.variablesReference > 0, "Library group ref must be positive for DAP expandability")

        // Expanding the library group with its ref should return individual parameters
        val paramsResponse = server.variables(VariablesArguments().also { it.variablesReference = testLibraryGroup.variablesReference }).get()
        assertEquals(2, paramsResponse.variables.size)

        val measurementPeriod = paramsResponse.variables.firstOrNull { it.name == "Measurement Period" }
        assertNotNull(measurementPeriod)
        assertEquals("Interval[2026-01-01, 2027-01-01]", measurementPeriod!!.value)
        assertEquals("Interval<DateTime>", measurementPeriod.type)

        val rate = paramsResponse.variables.firstOrNull { it.name == "Rate" }
        assertNotNull(rate)
        assertEquals("2.5", rate!!.value)
        assertEquals("Decimal", rate.type)
    }

    @Test
    fun `variables returns locals when variablesReference is 1 in streaming mode`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))
        state.stack.addFirst(State.ActivationFrame(null, null, null, 0L))
        state.topActivationFrame.variables.addFirst(
            org.opencds.cqf.cql.engine.execution.Variable("localVar").withValue("123"),
        )

        handler.onBeforeExpression(elm, state)

        val response = server.variables(VariablesArguments().also { it.variablesReference = 1 }).get()
        assertEquals(1, response.variables.size)
        assertEquals("localVar", response.variables[0].name)
        assertEquals("\"123\"", response.variables[0].value)
    }

    @Test
    fun `scopes returns Parameters and Locals in streaming mode when parameters exist`() {
        val server = setupServer()

        server.initParameterMetadata(
            mapOf(
                "TestLibrary" to
                    listOf(
                        CqlDebugServer.ParameterMetadata(
                            name = "Measurement Period",
                            type = "Interval<DateTime>",
                            defaultValue = "Interval[2026-01-01, 2027-01-01]",
                        ),
                    ),
            ),
        )

        val response = server.scopes(ScopesArguments().also { it.frameId = 0 }).get()
        assertEquals(3, response.scopes.size)
        assertNotNull(response.scopes.firstOrNull { it.name == "Parameters" })
        assertNotNull(response.scopes.firstOrNull { it.name == "Locals" })
        assertNotNull(response.scopes.firstOrNull { it.name == "Resolved Defines" })
    }

    @Test
    fun `scopes returns Test Case scope when launchArgs are present`() {
        val server = setupServer()
        server.initLaunchArgs(
            DebugLaunchArgs(
                libraryUri = "file:///test/TestLib.cql",
                libraryName = "TestLib",
                fhirVersion = "R4",
                testCaseName = "TestPatient",
                testCaseUri = "file:///test/testCases/TestPatient",
            ),
        )

        val response = server.scopes(ScopesArguments().also { it.frameId = 0 }).get()
        assertEquals(3, response.scopes.size)
        assertEquals("Locals", response.scopes[0].name)
        assertEquals(1, response.scopes[0].variablesReference)
        assertEquals("Resolved Defines", response.scopes[1].name)
        assertEquals(3, response.scopes[1].variablesReference)
        val testCaseScope = response.scopes.firstOrNull { it.name == "Test Case" }
        assertNotNull(testCaseScope)
        assertEquals(4, testCaseScope!!.variablesReference)
    }

    @Test
    fun `variables returns Test Case resources when variablesReference is 4`(
        @TempDir tempDir: Path,
    ) {
        val server = setupServer()
        val patient = Patient().also { it.id = "example" }
        val json = FhirContext.forR4().newJsonParser().encodeToString(patient)
        Files.writeString(tempDir.resolve("patient.json"), json)

        server.initLaunchArgs(
            DebugLaunchArgs(
                libraryUri = "file:///test/TestLib.cql",
                libraryName = "TestLib",
                fhirVersion = "R4",
                testCaseName = "TestPatient",
                testCaseUri = tempDir.toUri().toString(),
            ),
        )

        val response = server.variables(VariablesArguments().also { it.variablesReference = 4 }).get()
        assertNotNull(response.variables)
        assertEquals(1, response.variables.size)
        assertEquals("Patient/example", response.variables[0].name)
        assertEquals("Patient", response.variables[0].type)
        assertTrue(response.variables[0].variablesReference > 0)
    }

    @Test
    fun `variables uses filename fallback for anonymous resource`(
        @TempDir tempDir: Path,
    ) {
        val server = setupServer()
        val patient = Patient()
        val json = FhirContext.forR4().newJsonParser().encodeToString(patient)
        Files.writeString(tempDir.resolve("anonymous_patient.json"), json)

        server.initLaunchArgs(
            DebugLaunchArgs(
                libraryUri = "file:///test/TestLib.cql",
                libraryName = "TestLib",
                fhirVersion = "R4",
                testCaseName = "TestPatient",
                testCaseUri = tempDir.toUri().toString(),
            ),
        )

        val response = server.variables(VariablesArguments().also { it.variablesReference = 4 }).get()
        assertNotNull(response.variables)
        assertEquals(1, response.variables.size)
        assertEquals("anonymous_patient", response.variables[0].name)
    }

    @Test
    fun `variables swallows malformed files gracefully`(
        @TempDir tempDir: Path,
    ) {
        val server = setupServer()
        val patient = Patient().also { it.id = "valid" }
        val json = FhirContext.forR4().newJsonParser().encodeToString(patient)
        Files.writeString(tempDir.resolve("valid-patient.json"), json)
        Files.writeString(tempDir.resolve("malformed.json"), "{invalid json content")

        server.initLaunchArgs(
            DebugLaunchArgs(
                libraryUri = "file:///test/TestLib.cql",
                libraryName = "TestLib",
                fhirVersion = "R4",
                testCaseName = "TestPatient",
                testCaseUri = tempDir.toUri().toString(),
            ),
        )

        val response = server.variables(VariablesArguments().also { it.variablesReference = 4 }).get()
        assertNotNull(response.variables)
        assertEquals(1, response.variables.size)
        assertEquals("Patient/valid", response.variables[0].name)
    }

    @Test
    fun `variables sorts Test Case values alphabetically`(
        @TempDir tempDir: Path,
    ) {
        val server = setupServer()
        val patientB = Patient().also { it.id = "BPatient" }
        val patientA = Patient().also { it.id = "APatient" }
        val context = FhirContext.forR4()
        Files.writeString(tempDir.resolve("b-patient.json"), context.newJsonParser().encodeToString(patientB))
        Files.writeString(tempDir.resolve("a-patient.json"), context.newJsonParser().encodeToString(patientA))

        server.initLaunchArgs(
            DebugLaunchArgs(
                libraryUri = "file:///test/TestLib.cql",
                libraryName = "TestLib",
                fhirVersion = "R4",
                testCaseName = "TestPatient",
                testCaseUri = tempDir.toUri().toString(),
            ),
        )

        val response = server.variables(VariablesArguments().also { it.variablesReference = 4 }).get()
        assertNotNull(response.variables)
        assertEquals(2, response.variables.size)
        assertEquals("Patient/APatient", response.variables[0].name)
        assertEquals("Patient/BPatient", response.variables[1].name)
    }

    @Test
    fun `test case scope is absent when no launchArgs are set`() {
        val server = setupServer()

        val response = server.scopes(ScopesArguments().also { it.frameId = 0 }).get()
        assertEquals(2, response.scopes.size)
        assertEquals(null, response.scopes.firstOrNull { it.name == "Test Case" })
    }

    @Test
    fun `extractParameterMetadata handles interval type specifier`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))
        state.setParameters(null, mapOf("TestLibrary.Measurement Period" to "Interval[@2026-01-01, @2027-01-01)"))
        handler.onBeforeExpression(elm, state)

        server.initParameterMetadata(
            mapOf(
                "TestLibrary" to
                    listOf(
                        CqlDebugServer.ParameterMetadata(
                            name = "Measurement Period",
                            type = "Interval<DateTime>",
                            defaultValue = "Interval[2026-01-01, 2027-01-01]",
                        ),
                    ),
            ),
        )

        val scopesResponse = server.scopes(ScopesArguments().also { it.frameId = 0 }).get()
        assertNotNull(scopesResponse.scopes.firstOrNull { it.name == "Parameters" })

        // variables(2) returns library groups
        val groupsResponse = server.variables(VariablesArguments().also { it.variablesReference = 2 }).get()
        assertEquals(1, groupsResponse.variables.size)
        assertEquals("TestLibrary", groupsResponse.variables[0].name)
        assertEquals(null, groupsResponse.variables[0].type) // Library group has no type

        // Expanding the library group returns the actual parameters
        val paramsResponse = server.variables(VariablesArguments().also { it.variablesReference = groupsResponse.variables[0].variablesReference }).get()
        assertEquals(1, paramsResponse.variables.size)
        assertEquals("Measurement Period", paramsResponse.variables[0].name)
        assertEquals("Interval<DateTime>", paramsResponse.variables[0].type)
    }

    @Test
    fun `library groups get distinct positive refs and expansion yields correct params even when FHIR values present`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))

        // Parameters from two libraries (using dot prefix to trigger library grouping)
        state.setParameters(
            null,
            mapOf(
                "LibA.Param1" to "valueA1",
                "LibA.Param2" to "valueA2",
                "LibB.Param1" to "valueB1",
            ),
        )

        // FHIR Patient in context values — exercises >= 1000 branch
        val patient = Patient()
        patient.id = "collision-test"
        state.setContextValue("Patient", "collision-test")
        handler.contextResourcesByName["Patient"] = patient

        handler.onBeforeExpression(elm, state)

        // Load parameters into registry (mimics server's onPauseCallback)
        val paramTypes =
            mapOf(
                "LibA" to mapOf("Param1" to "String", "Param2" to "String"),
                "LibB" to mapOf("Param1" to "String"),
            )
        handler.runtimeRegistry.loadParameters(state, paramTypes)

        // variableReference == 2 returns library groups
        val groupsResponse = server.variables(VariablesArguments().also { it.variablesReference = 2 }).get()
        assertEquals(2, groupsResponse.variables.size)

        val libAGroup = groupsResponse.variables.firstOrNull { it.name == "LibA" }
        val libBGroup = groupsResponse.variables.firstOrNull { it.name == "LibB" }
        assertNotNull(libAGroup)
        assertNotNull(libBGroup)

        // Regression guard: library group refs must be positive for DAP expandability
        assertTrue(libAGroup!!.variablesReference > 0, "LibA group ref must be positive")
        assertTrue(libBGroup!!.variablesReference > 0, "LibB group ref must be positive")

        // Distinct refs
        assertTrue(libAGroup.variablesReference != libBGroup.variablesReference, "Library groups must have distinct refs")

        // Verify refs are in the >= 100000 band (reserved for library groups)
        assertTrue(libAGroup.variablesReference >= 100000, "LibA ref must be >= 100000, got ${libAGroup.variablesReference}")
        assertTrue(libBGroup.variablesReference >= 100000, "LibB ref must be >= 100000, got ${libBGroup.variablesReference}")

        // Expand LibA — should get Param1 and Param2
        val libAParams = server.variables(VariablesArguments().also { it.variablesReference = libAGroup.variablesReference }).get()
        assertEquals(2, libAParams.variables.size, "LibA should have 2 parameters")
        assertNotNull(libAParams.variables.firstOrNull { it.name == "Param1" })
        assertNotNull(libAParams.variables.firstOrNull { it.name == "Param2" })

        // Expand LibB — should get Param1
        val libBParams = server.variables(VariablesArguments().also { it.variablesReference = libBGroup.variablesReference }).get()
        assertEquals(1, libBParams.variables.size, "LibB should have 1 parameter")
        assertNotNull(libBParams.variables.firstOrNull { it.name == "Param1" })

        // Expand the FHIR Patient via its ref (>= 1000) — verify it still returns FHIR children,
        // proving the >= 100000 branch didn't capture it
        val localsResponse = server.variables(VariablesArguments().also { it.variablesReference = 1 }).get()
        val patientVar = localsResponse.variables.firstOrNull { it.name == "Patient" }
        assertNotNull(patientVar)
        assertTrue(
            patientVar!!.variablesReference in 1000 until 100000,
            "FHIR Patient ref should be in the 1000-99999 band, got ${patientVar.variablesReference}",
        )

        val patientChildren = server.variables(VariablesArguments().also { it.variablesReference = patientVar.variablesReference }).get()
        val childNames = patientChildren.variables.map { it.name }.toSet()
        assertTrue(childNames.contains("id"), "FHIR expansion should yield 'id', got $childNames")
    }

    @Test
    fun `streaming completion with exception sends terminated and exited with code 1`() {
        val client = Mockito.mock(org.eclipse.lsp4j.debug.services.IDebugProtocolClient::class.java)
        val server = makeServer()
        server.connect(client)

        server.triggerCompletion(RuntimeException("test error"))

        Mockito.verify(client).terminated(Mockito.any())
        val exitedCaptor = org.mockito.ArgumentCaptor.forClass(org.eclipse.lsp4j.debug.ExitedEventArguments::class.java)
        Mockito.verify(client).exited(exitedCaptor.capture())
        assertEquals(1, exitedCaptor.value.exitCode)
    }

    @Test
    fun `streaming completion without exception sends terminated and exited with code 0`() {
        val client = Mockito.mock(org.eclipse.lsp4j.debug.services.IDebugProtocolClient::class.java)
        val server = makeServer()
        server.connect(client)

        server.triggerCompletion(null)

        Mockito.verify(client).terminated(Mockito.any())
        val exitedCaptor = org.mockito.ArgumentCaptor.forClass(org.eclipse.lsp4j.debug.ExitedEventArguments::class.java)
        Mockito.verify(client).exited(exitedCaptor.capture())
        assertEquals(0, exitedCaptor.value.exitCode)
    }

    // -- variable expansion for FHIR resources --------------------------------

    @Test
    fun `variables Patient has non-zero variablesReference`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))

        val patient = Patient()
        patient.id = "pat-123"
        patient.birthDate = java.util.Calendar.getInstance().apply { set(1990, 1, 1) }.time
        patient.addName().setFamily("Smith").addGiven("John")

        state.setContextValue("Patient", patient.id!!)
        handler.contextResourcesByName["Patient"] = patient

        handler.onBeforeExpression(elm, state)

        val response = server.variables(VariablesArguments().also { it.variablesReference = 1 }).get()
        val patientVar = response.variables.firstOrNull { it.name == "Patient" }
        assertNotNull(patientVar)
        assertTrue(patientVar!!.variablesReference != 0, "Patient variable should have non-zero variablesReference")
    }

    @Test
    fun `variables expand Patient to show id birthDate name meta children`() {
        // Note: this test exercises the typeName=null (fallback) path because the test
        // server has no real CQL compiler, so variableTypeMap["Patient"] is null and
        // profileChildrenOf is not invoked. The profiled path (typeName="Patient") is
        // covered by integration tests where a real CQL library with a Patient define
        // is compiled. This test verifies that inherited FHIR fields (id, meta) are
        // visible when they are populated on the resource.
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))

        val patient = Patient()
        patient.id = "pat-123"
        patient.birthDate = java.util.Calendar.getInstance().apply { set(1990, 1, 1) }.time
        patient.addName().setFamily("Smith").addGiven("John")
        patient.meta = Meta().also { it.versionId = "1" }

        state.setContextValue("Patient", patient.id!!)
        handler.contextResourcesByName["Patient"] = patient

        handler.onBeforeExpression(elm, state)

        val rootResponse =
            server.variables(VariablesArguments().also { it.variablesReference = 1 }).get()
        val patientVar = rootResponse.variables.firstOrNull { it.name == "Patient" }
        assertNotNull(patientVar)
        val patientRef = patientVar!!.variablesReference
        assertTrue(patientRef >= 1000, "Patient ref should be >= 1000, got $patientRef")

        val childrenResponse = server.variables(VariablesArguments().also { it.variablesReference = patientRef }).get()
        val childNames = childrenResponse.variables.map { it.name }.toSet()

        assertTrue(childNames.contains("id"), "Children should include 'id', got $childNames")
        assertTrue(childNames.contains("birthDate"), "Children should include 'birthDate', got $childNames")
        assertTrue(childNames.contains("name"), "Children should include 'name', got $childNames")
        assertTrue(childNames.contains("meta"), "Children should include inherited 'meta', got $childNames")
    }

    @Test
    fun `variables primitive children have zero variablesReference`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))

        val patient = Patient()
        patient.id = "pat-123"

        state.setContextValue("Patient", patient.id!!)
        handler.contextResourcesByName["Patient"] = patient

        handler.onBeforeExpression(elm, state)

        val rootResponse = server.variables(VariablesArguments().also { it.variablesReference = 1 }).get()
        val patientVar = rootResponse.variables.firstOrNull { it.name == "Patient" }
        val patientRef = patientVar!!.variablesReference

        val childrenResponse = server.variables(VariablesArguments().also { it.variablesReference = patientRef }).get()
        val idChild = childrenResponse.variables.firstOrNull { it.name == "id" }
        assertNotNull(idChild)
        assertEquals(0, idChild!!.variablesReference, "Primitive 'id' should have zero variablesReference")
    }

    @Test
    fun `variables list-valued variable expands to indexed children`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))

        val encounter1 = org.hl7.fhir.r4.model.Encounter()
        encounter1.id = "enc-1"
        encounter1.status = org.hl7.fhir.r4.model.Encounter.EncounterStatus.FINISHED

        val encounter2 = org.hl7.fhir.r4.model.Encounter()
        encounter2.id = "enc-2"
        encounter2.status = org.hl7.fhir.r4.model.Encounter.EncounterStatus.INPROGRESS

        @Suppress("UNCHECKED_CAST")
        val encounters = listOf<org.hl7.fhir.instance.model.api.IBase>(encounter1, encounter2)
        state.setContextValue("Encounters", "Encounter/enc-1")
        handler.contextResourcesByName["Encounters"] = encounters

        handler.onBeforeExpression(elm, state)

        val rootResponse = server.variables(VariablesArguments().also { it.variablesReference = 1 }).get()
        val encountersVar = rootResponse.variables.firstOrNull { it.name == "Encounters" }
        assertNotNull(encountersVar)
        assertTrue(encountersVar!!.variablesReference != 0, "Encounters variable should have non-zero variablesReference")

        val childrenResponse = server.variables(VariablesArguments().also { it.variablesReference = encountersVar.variablesReference }).get()
        val childNames = childrenResponse.variables.map { it.name }

        assertTrue(childNames.contains("[0]"), "Children should include '[0]', got $childNames")
        assertTrue(childNames.contains("[1]"), "Children should include '[1]', got $childNames")
        assertEquals(2, childrenResponse.variables.size, "Should have exactly 2 children")
    }

    @Test
    fun `variables registry clears on new pause`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm1 =
            ExpressionDef().also {
                it.name = "FirstPause"
                it.locator = "1:1-1:10"
            }
        val state1 = State(Environment(null))
        val patient1 = Patient()
        patient1.id = "pat-first"
        state1.setContextValue("Patient", patient1.id!!)
        handler.contextResourcesByName["Patient"] = patient1
        handler.onBeforeExpression(elm1, state1)

        val response1 = server.variables(VariablesArguments().also { it.variablesReference = 1 }).get()
        val patientVar1 = response1.variables.firstOrNull { it.name == "Patient" }
        val firstRef = patientVar1!!.variablesReference
        assertTrue(firstRef >= 1000, "First pause patient ref should be >= 1000")

        handler.stepIn()
        val elm2 =
            ExpressionDef().also {
                it.name = "SecondPause"
                it.locator = "2:1-2:10"
            }
        val state2 = State(Environment(null))
        val patient2 = Patient()
        patient2.id = "pat-second"
        state2.setContextValue("Patient", patient2.id!!)
        handler.contextResourcesByName["Patient"] = patient2
        handler.onBeforeExpression(elm2, state2)

        val response2 = server.variables(VariablesArguments().also { it.variablesReference = 1 }).get()
        val patientVar2 = response2.variables.firstOrNull { it.name == "Patient" }
        val secondRef = patientVar2!!.variablesReference

        assertEquals(1000, secondRef, "variables() resets nextVarRef to 1000 for each ref=1 call")
    }

    // -- RuntimeValueRegistry: key collision ---------------------------------

    @Test
    fun `registry allows same-name entries in different categories`() {
        val server = setupServer()
        val handler = server.testHandler
        val reg = handler.runtimeRegistry

        // Same name, different categories
        reg.loadContextResource("Patient", "ctx-value", null)
        reg.putDefine("Patient", "define-value", null, null)

        // Both retrievable via find (stack variable priority > define > context > param)
        val found = reg.find("Patient")
        assertNotNull(found)
        assertEquals("define-value", found!!.value)
        assertEquals(RuntimeValueCategory.DEFINE, found.category)
    }

    // -- RuntimeValueRegistry: defines persist across steps ------------------

    @Test
    fun `defines persist across step boundary`() {
        val server = setupServer()
        val handler = server.testHandler

        // Step 1: evaluate a define
        handler.stepIn()
        val elm1 =
            ExpressionDef().also {
                it.name = "InitialPopulation"
                it.locator = "1:1-1:10"
            }
        val state1 = State(Environment(null))
        handler.onBeforeExpression(elm1, state1)
        handler.onExpressionDefEvaluated(elm1, state1, 42)

        // Step 2: step forward — define should still be available
        handler.stepIn()
        val elm2 =
            ExpressionDef().also {
                it.name = "SecondExpr"
                it.locator = "2:1-2:10"
            }
        val state2 = State(Environment(null))
        handler.onBeforeExpression(elm2, state2)

        val reg = handler.runtimeRegistry
        val found = reg.find("InitialPopulation")
        assertNotNull(found, "Define should persist across steps")
        assertEquals(42, found!!.value)
    }

    @Test
    fun `onExpressionDefEvaluated captures define in registry`() {
        val server = setupServer()
        val handler = server.testHandler

        val elm =
            ExpressionDef().also {
                it.name = "MyDefine"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))
        handler.onExpressionDefEvaluated(elm, state, "define-value")

        val found = handler.runtimeRegistry.find("MyDefine")
        assertNotNull(found)
        assertEquals("define-value", found!!.value)
        assertEquals(RuntimeValueCategory.DEFINE, found.category)
    }

    @Test
    fun `onAfterExpression does not capture ExpressionDef results`() {
        // Regression: the old dead branch in onAfterExpression that checked
        // `elm is ExpressionDef` was removed. Define results must now flow
        // through onExpressionDefEvaluated.
        val server = setupServer()
        val handler = server.testHandler

        val elm =
            ExpressionDef().also {
                it.name = "ShouldNotCapture"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))
        handler.onAfterExpression(elm, state, "some-value")

        assertNull(handler.runtimeRegistry.find("ShouldNotCapture"))
    }

    // -- RuntimeValueRegistry: context resources persist across steps --------

    @Test
    fun `context resources persist across step boundary`() {
        val server = setupServer()
        val handler = server.testHandler

        // Step 1: pause with a Patient context resource
        handler.stepIn()
        val elm1 =
            ExpressionDef().also {
                it.name = "FirstPause"
                it.locator = "1:1-1:10"
            }
        val state1 = State(Environment(null))
        val patient = Patient()
        patient.id = "persist-patient"
        state1.setContextValue("Patient", patient.id!!)
        handler.onBeforeExpression(elm1, state1)

        // Step 2: step forward — context resource should still be in registry
        handler.stepIn()
        val elm2 =
            ExpressionDef().also {
                it.name = "SecondPause"
                it.locator = "2:1-2:10"
            }
        val state2 = State(Environment(null))
        handler.onBeforeExpression(elm2, state2)

        val reg = handler.runtimeRegistry
        val found = reg.find("Patient")
        assertNotNull(found, "Context resource should persist across steps")
    }

    // -- Copy-value: clipboard evaluate for each category --------------------

    @Test
    fun `clipboard evaluate finds stack variable`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))
        state.stack.addFirst(State.ActivationFrame(null, null, null, 0L))
        state.topActivationFrame.variables.addFirst(
            org.opencds.cqf.cql.engine.execution.Variable("myVar").withValue("hello"),
        )
        handler.onBeforeExpression(elm, state)

        val response =
            server.evaluate(
                EvaluateArguments().also {
                    it.expression = "myVar"
                    it.context = "clipboard"
                    it.frameId = 0
                },
            ).get()

        assertEquals("\"hello\"", response.result)
    }

    @Test
    fun `clipboard evaluate finds define`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "Numerator"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))
        handler.onBeforeExpression(elm, state)
        handler.onExpressionDefEvaluated(elm, state, 100)

        val response =
            server.evaluate(
                EvaluateArguments().also {
                    it.expression = "Numerator"
                    it.context = "clipboard"
                    it.frameId = 0
                },
            ).get()

        assertEquals("100", response.result)
    }

    @Test
    fun `clipboard evaluate finds context resource`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))
        val patient = Patient()
        patient.id = "pat-copy"
        state.setContextValue("Patient", patient.id!!)
        handler.onBeforeExpression(elm, state)

        val response =
            server.evaluate(
                EvaluateArguments().also {
                    it.expression = "Patient"
                    it.context = "clipboard"
                    it.frameId = 0
                },
            ).get()

        assertNotNull(response.result)
        assert(response.result.contains("pat-copy"))
    }

    @Test
    fun `clipboard evaluate returns notAvailable for unknown`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))
        handler.onBeforeExpression(elm, state)

        val response =
            server.evaluate(
                EvaluateArguments().also {
                    it.expression = "nonExistent"
                    it.context = "clipboard"
                    it.frameId = 0
                },
            ).get()

        assertEquals("not available", response.result)
    }

    // -- Copy-value: nested FHIR child variable ------------------------------

    @Test
    fun `clipboard evaluate finds expanded FHIR child variable via varRefs tree`() {
        val server = setupServer()
        val handler = server.testHandler

        handler.stepIn()
        val elm =
            ExpressionDef().also {
                it.name = "TestExpr"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))

        val patient = Patient()
        patient.id = "pat-123"
        patient.addName().setFamily("Smith").addGiven("John")

        state.setContextValue("Patient", patient.id!!)
        handler.contextResourcesByName["Patient"] = patient
        handler.onBeforeExpression(elm, state)

        // First, variables(ref=1) populates varRefs for the Patient
        val rootResponse = server.variables(VariablesArguments().also { it.variablesReference = 1 }).get()
        val patientVar = rootResponse.variables.firstOrNull { it.name == "Patient" }
        assertNotNull(patientVar)
        val patientRef = patientVar!!.variablesReference
        assertTrue(patientRef >= 1000)

        // Expand Patient to populate varRefs with children
        server.variables(VariablesArguments().also { it.variablesReference = patientRef }).get()

        // Now clipboard evaluate should find "name" as a child of Patient
        val response =
            server.evaluate(
                EvaluateArguments().also {
                    it.expression = "name"
                    it.context = "clipboard"
                    it.frameId = 0
                },
            ).get()

        assertNotNull(response.result)
        assert(response.result.contains("Smith"))
    }

    // -- Registry reset ------------------------------------------------------

    @Test
    fun `registry reset clears all values`() {
        val server = setupServer()
        val handler = server.testHandler
        val reg = handler.runtimeRegistry

        reg.putDefine("SomeDefine", 42, null, null)
        reg.loadContextResource("Patient", "value", null)

        reg.reset()

        assertNull(reg.find("SomeDefine"))
        assertNull(reg.find("Patient"))
        assertTrue(reg.getDefines().isEmpty())
        assertTrue(reg.getContextResources().isEmpty())
    }

    // -- clearStackVariables preserves persistent values ----------------------

    @Test
    fun `clearStackVariables clears only stack variables`() {
        val server = setupServer()
        val handler = server.testHandler
        val reg = handler.runtimeRegistry

        reg.putStackVariable("tempVar", "temp", null)
        reg.putDefine("persistentDefine", "def-value", null, null)
        reg.loadContextResource("Patient", "res-value", null)

        reg.clearStackVariables()

        assertNull(reg.find("tempVar"), "Stack variable should be cleared")
        assertEquals("def-value", reg.find("persistentDefine")!!.value, "Define should persist")
        assertEquals("res-value", reg.find("Patient")!!.value, "Context resource should persist")
    }

    // -- Registry: parameter persistence across steps ------------------------

    @Test
    fun `parameters persist across step boundary when state has no parameters on second pause`() {
        val server = setupServer()
        val handler = server.testHandler
        val reg = handler.runtimeRegistry

        // First pause: state with parameters
        handler.stepIn()
        val elm1 =
            ExpressionDef().also {
                it.name = "FirstPause"
                it.locator = "1:1-1:10"
            }
        val state1 = State(Environment(null))
        state1.setParameters(
            null,
            mapOf(
                "MyLib.MeasurementPeriod" to "Interval[2026-01-01, 2027-01-01]",
                "MyLib.Threshold" to 50,
            ),
        )
        // Simulate what the server's onPauseCallback does
        val paramTypes1 = mapOf("MyLib" to mapOf("MeasurementPeriod" to "Interval<DateTime>", "Threshold" to "Integer"))
        reg.loadParameters(state1, paramTypes1)

        val paramsByLib = reg.getParametersByLibrary()
        assertEquals(1, paramsByLib.size, "Should have one library group")
        val myLibParams = paramsByLib["MyLib"]
        assertNotNull(myLibParams)
        assertEquals(2, myLibParams!!.size)

        val mp = myLibParams.find { it.name == "MeasurementPeriod" }
        assertNotNull(mp)
        assertEquals("Interval<DateTime>", mp!!.type)
        assertEquals("Interval[2026-01-01, 2027-01-01]", mp.value)

        val threshold = myLibParams.find { it.name == "Threshold" }
        assertNotNull(threshold)
        assertEquals(50, threshold!!.value)
        assertEquals("Integer", threshold.type)

        // Second pause: state with NO parameters — loadParameters should be no-op
        handler.stepIn()
        val elm2 =
            ExpressionDef().also {
                it.name = "SecondPause"
                it.locator = "2:1-2:10"
            }
        val state2 = State(Environment(null))
        // state2.parameters is empty
        reg.loadParameters(state2) // no type map — idempotent, no-op

        // Parameters must still be present
        val paramsAfter = reg.getParametersByLibrary()
        assertEquals(1, paramsAfter.size, "Parameters should persist after second pause")
        assertEquals(50, paramsAfter["MyLib"]!!.find { it.name == "Threshold" }!!.value)
    }

    @Test
    fun `loadParameters is idempotent`() {
        val reg = RuntimeValueRegistry()

        val state = State(Environment(null))
        state.setParameters(null, mapOf("Lib.Param1" to "firstValue"))
        val types = mapOf("Lib" to mapOf("Param1" to "String"))

        // First load
        reg.loadParameters(state, types)
        val afterFirst = reg.getParametersByLibrary()
        assertEquals(1, afterFirst.size)
        assertEquals("firstValue", afterFirst["Lib"]!!.first().value)

        // Second load with different values — must be ignored
        state.setParameters(null, mapOf("Lib.Param1" to "overwritten"))
        reg.loadParameters(state, types)
        val afterSecond = reg.getParametersByLibrary()
        assertEquals(1, afterSecond.size, "Should still be one parameter")
        assertEquals(
            "firstValue",
            afterSecond["Lib"]!!.first().value,
            "Second load should be ignored (idempotent)",
        )
    }

    @Test
    fun `parameter and stack variable with same name are independent`() {
        val reg = RuntimeValueRegistry()

        // Load a parameter named "Patient"
        val state = State(Environment(null))
        state.setParameters(null, mapOf("Lib.Patient" to "param-patient-value"))
        val types = mapOf("Lib" to mapOf("Patient" to "String"))
        reg.loadParameters(state, types)

        // Add a stack variable with the same name
        reg.putStackVariable("Patient", "stack-patient-value", null)

        // find() should return stack variable (highest priority)
        val found = reg.find("Patient")
        assertNotNull(found)
        assertEquals("stack-patient-value", found!!.value)
        assertEquals(RuntimeValueCategory.STACK_VARIABLE, found.category)

        // Each category should still contain their own entry
        val stackVars = reg.getStackVariables()
        assertEquals(1, stackVars.size)
        assertEquals("stack-patient-value", stackVars.first().value)

        val params = reg.getParametersByLibrary()
        assertEquals("param-patient-value", params["Lib"]!!.first().value)
    }

    @Test
    fun `find with library name returns correct library-scoped define`() {
        val reg = RuntimeValueRegistry()

        val libAId = VersionedIdentifier().also { it.id = "LibA" }
        val libBId = VersionedIdentifier().also { it.id = "LibB" }
        reg.putDefine("Foo", "valueA", null, libAId)
        reg.putDefine("Foo", "valueB", null, libBId)

        val foundA = reg.find("Foo", "LibA")
        assertNotNull(foundA)
        assertEquals("valueA", foundA!!.value)
        assertEquals("LibA", foundA.libraryName)

        val foundB = reg.find("Foo", "LibB")
        assertNotNull(foundB)
        assertEquals("valueB", foundB!!.value)
        assertEquals("LibB", foundB.libraryName)
    }

    @Test
    fun `find with library name returns null for wrong library`() {
        val reg = RuntimeValueRegistry()

        val libId = VersionedIdentifier().also { it.id = "MyLib" }
        reg.putDefine("Target", "present", null, libId)

        assertNull(reg.find("Target", "OtherLib"))
    }

    @Test
    fun `onExpressionDefEvaluated captures define under correct library`() {
        val server = setupServer()
        val handler = server.testHandler
        val reg = handler.runtimeRegistry

        val elm =
            ExpressionDef().also {
                it.name = "LibDefine"
                it.locator = "1:1-1:10"
            }
        val libId =
            VersionedIdentifier().also {
                it.id = "Common"
                it.version = "1.0.0"
            }
        val library = Library().also { it.identifier = libId }
        val state = State(Environment(null))
        state.init(library)
        handler.onExpressionDefEvaluated(elm, state, "cross-lib-value")

        // Unqualified find should work
        assertEquals("cross-lib-value", reg.find("LibDefine")!!.value)
        // Library-qualified find should also work
        assertEquals("cross-lib-value", reg.find("LibDefine", "Common")!!.value)
    }

    @Test
    fun `onExpressionDefEvaluated does not interfere with stack variables`() {
        val server = setupServer()
        val handler = server.testHandler
        val reg = handler.runtimeRegistry

        reg.putStackVariable("tempVar", "stack-value", null)

        val elm =
            ExpressionDef().also {
                it.name = "SomeDefine"
                it.locator = "1:1-1:10"
            }
        val state = State(Environment(null))
        handler.onExpressionDefEvaluated(elm, state, "define-value")

        // Stack variable should still be findable
        assertEquals("stack-value", reg.find("tempVar")!!.value)
        // Define should also be findable
        assertEquals("define-value", reg.find("SomeDefine")!!.value)
    }

    @Test
    fun `find with library name falls back to unqualified when libraryName is null`() {
        val reg = RuntimeValueRegistry()

        val libId = VersionedIdentifier().also { it.id = "MyLib" }
        reg.putDefine("MyDefine", "lib-value", null, libId)

        // Find without library should still work (backward compat)
        val found = reg.find("MyDefine")
        assertNotNull(found)
        assertEquals("lib-value", found!!.value)
    }

    @Test
    fun `formatVariableValue on Enumeration returns lowercased FHIR code`() {
        val server = makeServer()
        val gson = Gson()

        // Use getStatusElement() which returns the Enumeration<EncounterStatus> wrapper
        // (an IPrimitiveType), not the raw Java enum.
        val encounter = Encounter()
        encounter.status = Encounter.EncounterStatus.FINISHED
        val statusValue = encounter.getStatusElement()

        val method = CqlDebugServer::class.java.getDeclaredMethod(
            "formatVariableValue", Any::class.java, Gson::class.java
        )
        method.isAccessible = true
        val result = method.invoke(server, statusValue, gson) as String

        assertEquals("finished", result)
    }

    @Test
    fun `extractPropertyValue returns IPrimitiveType wrapper not raw enum`() {
        val server = makeServer()
        val gson = Gson()

        val encounter = Encounter()
        encounter.status = Encounter.EncounterStatus.FINISHED

        val extractMethod = CqlDebugServer::class.java.getDeclaredMethod(
            "extractPropertyValue", IBase::class.java, String::class.java
        )
        extractMethod.isAccessible = true
        val result = extractMethod.invoke(server, encounter, "status")

        // Should return Enumeration<EncounterStatus> (an IPrimitiveType), not EncounterStatus.FINISHED
        assertTrue(result is org.hl7.fhir.instance.model.api.IPrimitiveType<*>,
            "Expected IPrimitiveType wrapper, got ${result?.javaClass?.name}")

        // Verify it formats as lowercase FHIR code
        val formatMethod = CqlDebugServer::class.java.getDeclaredMethod(
            "formatVariableValue", Any::class.java, Gson::class.java
        )
        formatMethod.isAccessible = true
        val formatted = formatMethod.invoke(server, result, gson) as String

        assertEquals("finished", formatted)
    }
}
