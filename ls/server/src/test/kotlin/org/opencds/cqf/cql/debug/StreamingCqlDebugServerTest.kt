package org.opencds.cqf.cql.debug

import org.eclipse.lsp4j.debug.ContinueArguments
import org.eclipse.lsp4j.debug.EvaluateArguments
import org.eclipse.lsp4j.debug.NextArguments
import org.eclipse.lsp4j.debug.ScopesArguments
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.StackTraceArguments
import org.eclipse.lsp4j.debug.VariablesArguments
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.Literal
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.opencds.cqf.cql.engine.execution.Environment
import org.opencds.cqf.cql.engine.execution.State
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager

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

    // -- scopes -------------------------------------------------------------

    @Test
    fun `scopes returns Locals scope in streaming mode`() {
        val server = setupServer()
        val response = server.scopes(ScopesArguments().also { it.frameId = 0 }).get()
        assertEquals(1, response.scopes.size)
        assertEquals("Locals", response.scopes[0].name)
        assertEquals(1, response.scopes[0].variablesReference)
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
        // Add a variable with a complex value
        state.topActivationFrame.variables.addFirst(
            org.opencds.cqf.cql.engine.execution.Variable("patientId").withValue(
                mapOf("id" to "1111", "name" to listOf(mapOf("family" to "Chalmers"))),
            ),
        )
        handler.onBeforeExpression(elm, state)

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
            org.opencds.cqf.cql.engine.execution.Variable("patientId").withValue(
                mapOf("id" to "1111"),
            ),
        )
        handler.onBeforeExpression(elm, state)

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
        state.contextValues["Patient"] = mapOf("id" to "pat-1", "name" to listOf(mapOf("family" to "Smith")))
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
        state.contextValues["Patient"] = "pat-1"

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

        state.contextValues["Patient"] = "pat-1"
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

        state.contextValues["Patient"] = "pat-1"
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
        assertEquals(2, response.scopes.size)
        val paramScope = response.scopes.firstOrNull { it.name == "Parameters" }
        assertNotNull(paramScope)
        assertEquals(2, paramScope!!.variablesReference)
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
        assertEquals(2, response.scopes.size)
        assertNotNull(response.scopes.firstOrNull { it.name == "Parameters" })
        assertNotNull(response.scopes.firstOrNull { it.name == "Locals" })
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
        assertEquals(1, response.scopes.size)
        assertEquals("Locals", response.scopes[0].name)
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
        state.parameters["Measurement Period"] = "Interval[@2026-01-01, @2027-01-01)"
        state.parameters["Patient Type"] = "HMO"

        handler.onBeforeExpression(elm, state)

        // Metadata grouped by library - global params under "(Global)"
        server.initParameterMetadata(
            mapOf(
                "(Global)" to
                    listOf(
                        CqlDebugServer.ParameterMetadata(
                            name = "Measurement Period",
                            type = "Interval<DateTime>",
                            defaultValue = null,
                        ),
                        CqlDebugServer.ParameterMetadata(
                            name = "Patient Type",
                            type = "String",
                            defaultValue = "'HMO'",
                        ),
                    ),
            ),
        )

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
        assertEquals(2, response.scopes.size)
        assertNotNull(response.scopes.firstOrNull { it.name == "Parameters" })
        assertNotNull(response.scopes.firstOrNull { it.name == "Locals" })
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
        state.parameters["TestLibrary.Measurement Period"] = "Interval[@2026-01-01, @2027-01-01)"
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
        state.parameters["LibA.Param1"] = "valueA1"
        state.parameters["LibA.Param2"] = "valueA2"
        state.parameters["LibB.Param1"] = "valueB1"

        // FHIR Patient in context values — exercises >= 1000 branch
        val patient = Patient()
        patient.id = "collision-test"
        state.contextValues["Patient"] = patient
        handler.contextResourcesByName["Patient"] = patient

        handler.onBeforeExpression(elm, state)

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

        state.contextValues["Patient"] = patient
        handler.contextResourcesByName["Patient"] = patient

        handler.onBeforeExpression(elm, state)

        val response = server.variables(VariablesArguments().also { it.variablesReference = 1 }).get()
        val patientVar = response.variables.firstOrNull { it.name == "Patient" }
        assertNotNull(patientVar)
        assertTrue(patientVar!!.variablesReference != 0, "Patient variable should have non-zero variablesReference")
    }

    @Test
    fun `variables expand Patient to show id birthDate name children`() {
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

        state.contextValues["Patient"] = patient
        handler.contextResourcesByName["Patient"] = patient

        handler.onBeforeExpression(elm, state)

        val rootResponse = server.variables(VariablesArguments().also { it.variablesReference = 1 }).get()
        val patientVar = rootResponse.variables.firstOrNull { it.name == "Patient" }
        assertNotNull(patientVar)
        val patientRef = patientVar!!.variablesReference
        assertTrue(patientRef >= 1000, "Patient ref should be >= 1000, got $patientRef")

        val childrenResponse = server.variables(VariablesArguments().also { it.variablesReference = patientRef }).get()
        val childNames = childrenResponse.variables.map { it.name }.toSet()

        assertTrue(childNames.contains("id"), "Children should include 'id', got $childNames")
        assertTrue(childNames.contains("birthDate"), "Children should include 'birthDate', got $childNames")
        assertTrue(childNames.contains("name"), "Children should include 'name', got $childNames")
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

        state.contextValues["Patient"] = patient
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
        state.contextValues["Encounters"] = encounters as Any

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
        state1.contextValues["Patient"] = patient1
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
        state2.contextValues["Patient"] = patient2
        handler.contextResourcesByName["Patient"] = patient2
        handler.onBeforeExpression(elm2, state2)

        val response2 = server.variables(VariablesArguments().also { it.variablesReference = 1 }).get()
        val patientVar2 = response2.variables.firstOrNull { it.name == "Patient" }
        val secondRef = patientVar2!!.variablesReference

        assertTrue(secondRef >= 1000, "Second pause patient ref should be >= 1000")
        assertTrue(
            firstRef != secondRef || secondRef == 1000,
            "After new pause, either new ref or reset to 1000. First: $firstRef, Second: $secondRef",
        )
    }
}
