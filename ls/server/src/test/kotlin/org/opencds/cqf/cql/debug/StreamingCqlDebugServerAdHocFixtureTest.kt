package org.opencds.cqf.cql.debug

import org.eclipse.lsp4j.debug.EvaluateArguments
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.Library
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.opencds.cqf.cql.engine.debug.BreakpointAction
import org.opencds.cqf.cql.engine.execution.Environment
import org.opencds.cqf.cql.engine.execution.State
import org.opencds.cqf.cql.engine.runtime.ClassInstance
import org.opencds.cqf.cql.engine.runtime.Code
import org.opencds.cqf.cql.engine.runtime.Concept
import org.opencds.cqf.cql.engine.runtime.Interval
import org.opencds.cqf.cql.engine.runtime.Quantity
import org.opencds.cqf.cql.engine.runtime.Ratio
import org.opencds.cqf.cql.engine.runtime.Tuple
import org.opencds.cqf.cql.engine.runtime.Value
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService
import java.math.BigDecimal
import java.net.URI
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.xml.namespace.QName
import org.opencds.cqf.cql.engine.runtime.Date as CqlDate
import org.opencds.cqf.cql.engine.runtime.DateTime as CqlDateTime
import org.opencds.cqf.cql.engine.runtime.Decimal as CqlDecimal
import org.opencds.cqf.cql.engine.runtime.Integer as CqlInteger
import org.opencds.cqf.cql.engine.runtime.List as CqlList
import org.opencds.cqf.cql.engine.runtime.String as CqlString

/**
 * Fixture-backed coverage for the streaming debugger's ad-hoc evaluate (repl/watch).
 *
 * Mirrors the harness shape of [StreamingCqlDebugServerTest] (private `TestStreamingServer`,
 * `parkEngineThread`, `pauseState`, `registerStackVar`) but pauses inside real compiled test
 * fixtures (`AdHoc*.cql` served by [TestContentService]) instead of inline source text. Each
 * fixture is precompiled through the LS itself ([CqlCompilationManager.compile]), so the files
 * double as compile-safety fixtures.
 *
 * Groups (see `src/test/kotlin/.../debug/AD_HOC_EVAL_COVERAGE.md`):
 * 1. repl context works identically under `watch` context
 * 2. query constructs (retrieve-free defines, from/let/where/sort, interval + tuple literals)
 * 3. operand allow-list pass-throughs + temporal aliases bind as real typed values
 * 4. included-library Tupled alias whose translator type is resolved via the qualified key
 * 5. ad-hoc eval timeout when no engine thread is parked → `not available`
 * 6. FHIR-model fixtures: runtime-degraded retrieves, class-instance property chains
 * 7. `extractIdentifiers` tokenization edge cases
 * 8. VitalSigns fixture compile (all 5 idiom fragments) + code-equivalence `~` + degrades
 */
class StreamingCqlDebugServerAdHocFixtureTest {
    /**
     * Subclass that pre-sets [streamingHandler] to a [testHandler] and stubs
     * [executeLaunchStreaming] so [launch] is safe to call. Same shape as the private
     * `TestStreamingServer` in `StreamingCqlDebugServerTest` (which is not accessible from here),
     * plus a retained reference to the real [CqlCompilationManager] so paused states can be built
     * from the actually-compiled fixture ELM.
     */
    private class TestStreamingServer(
        val cm: CqlCompilationManager,
        private val cs: ContentService,
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

        fun addLibrarySource(
            libId: String,
            uri: URI,
        ) {
            val field = CqlDebugServer::class.java.getDeclaredField("librarySourceMap")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(this) as ConcurrentHashMap<String, URI>)[libId] = uri
        }
    }

    // ------------------------------------------------------------------ harness

    /**
     * Builds a real [CqlCompilationManager] over [TestContentService], precompiles the named
     * fixture (fail-fast — the fixture must compile), and wires a server + connected client with
     * the launch URI pinned to the fixture. The handler gets a real translator [org.cqframework.cql.cql2elm.LibraryManager]
     * so synthetic ad-hoc compilation succeeds.
     */
    private fun fixtureServer(fixtureName: String): Pair<TestStreamingServer, URI> {
        val fixtureUri = URI("/org/opencds/cqf/cql/ls/server/$fixtureName.cql")
        val cs = TestContentService()
        val cm =
            CqlCompilationManager(
                cs,
                CompilerOptionsManager(cs),
                IgContextManager(cs),
                LibraryResolutionManager(emptyList()),
            )
        // Precompile so getSourceText(uri) has an entry in the cache AND the fixture itself is
        // exercised for compile safety. A failed compile surfaces here, not in a later assertion.
        val compiler = cm.compile(fixtureUri) ?: throw AssertionError("fixture $fixtureName returned null compiler")
        val errors =
            compiler.exceptions.filter {
                it.severity == org.cqframework.cql.cql2elm.CqlCompilerException.ErrorSeverity.Error
            }
        check(errors.isEmpty()) { "fixture $fixtureName failed to compile: ${errors.joinToString { it.message ?: "" }}" }
        assertNotNull(compiler.library, "fixture $fixtureName produced no ELM: ${compiler.exceptions}")

        val client = mock(IDebugProtocolClient::class.java)
        val server = TestStreamingServer(cm, cs, mock(IgContextManager::class.java), mock(LibraryResolutionManager::class.java))
        server.connect(client)
        server.setLaunchUri(fixtureUri.toString())

        val modelManager = org.cqframework.cql.cql2elm.ModelManager()
        server.testHandler.libraryManager = org.cqframework.cql.cql2elm.LibraryManager(modelManager)
        return server to fixtureUri
    }

    /**
     * Builds the paused state's library from the ACTUAL compiled fixture so ExpressionRefs in
     * ad-hoc expressions (e.g. `Count("Items")`) resolve to the real define ELM.
     */
    private fun pauseState(
        server: TestStreamingServer,
        fixtureUri: URI,
    ): Pair<Library, State> {
        val compiled = server.cm.compile(fixtureUri)!!.library!!
        val state = State(Environment(null))
        state.init(compiled)
        state.stack.addFirst(State.ActivationFrame(null, null, null, 0L))
        return compiled to state
    }

    /**
     * Pauses on the handler (arming the resume latch), then parks a daemon engine thread in
     * [StreamingBreakpointHandler.waitForResume] so ad-hoc eval requests get drained. The returned
     * thread must be released with [StreamingBreakpointHandler.release] + [Thread.join].
     */
    private fun parkEngineThread(
        handler: StreamingBreakpointHandler,
        elm: ExpressionDef,
        state: State,
    ): Thread {
        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(elm, state))
        val engineReady = java.util.concurrent.CyclicBarrier(2)
        val engineThread =
            Thread {
                engineReady.await()
                handler.waitForResume()
            }
        engineThread.isDaemon = true
        engineThread.start()
        engineReady.await()
        Thread.sleep(50) // let the engine thread enter waitForResume and start polling
        return engineThread
    }

    /** Registers a transient stack variable so query-alias resolution sees it as live. */
    private fun registerStackVar(
        handler: StreamingBreakpointHandler,
        name: String,
        value: Any,
    ) {
        handler.runtimeRegistry.putStackVariable(name, value, null)
    }

    /**
     * Full pause + park + evaluate round trip against a compiled fixture, in the same order the
     * real debugger uses: pause, THEN register stack vars (capturePauseState clears them).
     */
    private fun evaluateInFixture(
        server: TestStreamingServer,
        fixtureUri: URI,
        expression: String,
        typeMap: Map<String, String> = emptyMap(),
        context: String = "repl",
        register: (StreamingBreakpointHandler) -> Unit = {},
    ): String {
        val handler = server.testHandler
        handler.variableTypeMap = typeMap
        handler.stepIn()
        val (_, state) = pauseState(server, fixtureUri)
        val elm =
            ExpressionDef().also {
                it.name = "X"
                it.locator = "5:1-5:10"
            }
        val engineThread = parkEngineThread(handler, elm, state)
        try {
            register(handler)
            val response =
                server.evaluate(
                    EvaluateArguments().also {
                        it.expression = expression
                        it.context = context
                        it.frameId = 0
                    },
                ).get(15, TimeUnit.SECONDS)
            assertNotNull(response.result)
            return response.result
        } finally {
            handler.release()
            engineThread.join(2000)
        }
    }

    /** Builds a FHIR-shaped `Encounter` ClassInstance (id → value chain) as the engine does. */
    private fun fhirClassInstance(
        type: String,
        elements: Map<String, Any?>,
    ): ClassInstance {
        val values =
            elements.mapValues { (_, v) ->
                when (v) {
                    is Value -> v
                    is String -> CqlString(v)
                    else -> CqlString(v.toString())
                }
            }
        return ClassInstance(QName("urn:hl7-org:elm-types:r1", type), values.toMutableMap())
    }

    private fun fhirEncounterClassInstance(id: String): ClassInstance =
        fhirClassInstance("Encounter", mapOf("id" to fhirClassInstance("id", mapOf("value" to id))))

    /** Invokes the private [CqlDebugServer.extractIdentifiers] helper via reflection. */
    private fun extractIdentifiers(
        server: CqlDebugServer,
        expression: String,
    ): List<String> {
        val method = CqlDebugServer::class.java.getDeclaredMethod("extractIdentifiers", String::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(server, expression) as List<String>
    }

    private val uriBasics = URI("/org/opencds/cqf/cql/ls/server/AdHocQueryBasics.cql")
    private val uriOperandTypes = URI("/org/opencds/cqf/cql/ls/server/AdHocOperandTypes.cql")
    private val uriFhir = URI("/org/opencds/cqf/cql/ls/server/AdHocFhirQuery.cql")
    private val uriVital = URI("/org/opencds/cqf/cql/ls/server/AdHocVitalSigns.cql")

    // ------------------------------------------------------------------ group 1

    @Test
    fun `G1 repl constructs also evaluate under watch context`() {
        val (server, uri) = fixtureServer("AdHocQueryBasics")

        // Plain arithmetic under watch.
        assertEquals("3", evaluateInFixture(server, uri, "1 + 2", context = "watch"))

        // Define reference (ExpressionRef into the paused library) under watch.
        assertEquals("3", evaluateInFixture(server, uri, "Count(\"Items\")", context = "watch"))

        // Query-alias binding under watch.
        assertEquals(
            "'hello world'",
            evaluateInFixture(
                server,
                uri,
                "S + ' world'",
                typeMap = mapOf("S" to "System.String"),
                context = "watch",
            ) { h -> registerStackVar(h, "S", "hello") },
        )
    }

    // ------------------------------------------------------------------ group 2

    @Test
    fun `G2 query constructs evaluate against the compiled fixture library`() {
        val (server, uri) = fixtureServer("AdHocQueryBasics")

        // Define ref.
        assertEquals("3", evaluateInFixture(server, uri, "Count(\"Items\")"))
        // where clause.
        assertEquals("2", evaluateInFixture(server, uri, "Count(from \"Items\" I where I.score >= 90)"))
        // let clause inside the ad-hoc query.
        assertEquals("2", evaluateInFixture(server, uri, "Count(from \"Items\" I let Doubled: I.score * 2 where Doubled > 180)"))
        // exists + where.
        assertEquals("true", evaluateInFixture(server, uri, "exists (from \"Items\" I where I.score >= 95)"))
        // sort by desc + property access on First() tuple. NOTE: cql-to-elm resolves sort-by
        // expressions against the query RESULT element (unqualified), not the from-alias.
        assertEquals("'charlie'", evaluateInFixture(server, uri, "First(from \"Items\" I sort by score desc).name"))
        // Existing defines that wrap the same constructs.
        assertEquals("2", evaluateInFixture(server, uri, "Count(\"Sorted By Score\")"))
        assertEquals("2", evaluateInFixture(server, uri, "Count(\"Let and Where\")"))
        // System literal types.
        assertEquals("[1, 10]", evaluateInFixture(server, uri, "Interval[1, 10]"))
        assertEquals("Tuple { a: 1, b: 'two' }", evaluateInFixture(server, uri, "Tuple { a: 1, b: 'two' }"))
    }

    @Test
    fun `G2 expression starting with a quoted define evaluates fully, not as the define alone`() {
        val (server, uri) = fixtureServer("AdHocQueryBasics")
        val register = { h: StreamingBreakpointHandler ->
            registerStackVar(h, "Interval Example", Interval(CqlInteger(1), true, CqlInteger(10), true))
        }

        val typeMap = mapOf("Interval Example" to "interval<System.Integer>")

        // The quoted root resolves in the registry; it must not short-circuit the operator.
        assertEquals("[1, 10]", evaluateInFixture(server, uri, "\"Interval Example\"", register = register))
        assertEquals("true", evaluateInFixture(server, uri, "\"Interval Example\" contains 5", typeMap = typeMap, register = register))
        assertEquals("false", evaluateInFixture(server, uri, "\"Interval Example\" contains 50", typeMap = typeMap, register = register))
        assertEquals("true", evaluateInFixture(server, uri, "\"Interval Example\" = Interval[1, 10]", typeMap = typeMap, register = register))
    }

    @Test
    fun `G2 terminology literals evaluate against the compiled fixture library`() {
        // AdHocTerminology carries valueset/Code/Concept/Quantity/Ratio literals and a nested
        // List<Tuple> define, evaluated as ad-hoc expressions in bare System scope (no `using FHIR`).
        val (server, uri) = fixtureServer("AdHocTerminology")

        // Terminology VALUE evaluation: a code REFERENCE wrapped in a function call compiles and
        // yields a System.Code; `.code` walks the element chain. (Function-wrapped forms bypass
        // the identifier-shortcut below.)
        assertEquals("'8480-6'", evaluateInFixture(server, uri, "First({ \"Systolic BP Code\" }).code"))

        // Quantity literal + alias define (from-query over a list-of-quantities literal).
        assertEquals("2", evaluateInFixture(server, uri, "Count(\"Quantity Alias\")"))

        // Nested List<Tuple{...}> define.
        assertEquals("2", evaluateInFixture(server, uri, "Count(\"Nested Tuples\")"))

        // Define-rooted identifier-shaped expressions strand in resolveMissingIdentifier BEFORE
        // ad-hoc compilation: the root is a fixture define (not a registry stack variable), so the
        // debugger reports "doesn't exist" instead of evaluating. Pinned as current behavior — see
        // AD_HOC_EVAL_COVERAGE.md known gaps.
        assertEquals(
            "Identifier doesn't exist (CQL is case-sensitive)",
            evaluateInFixture(server, uri, "\"Code Selector\".code"),
        )
        assertEquals(
            "Identifier doesn't exist (CQL is case-sensitive)",
            evaluateInFixture(server, uri, "\"Ratio Literal\".numerator.value"),
        )
    }

    // ------------------------------------------------------------------ group 3

    @Test
    fun `G3 operand allow-list pass-through evaluates property chains`() {
        val (server, uri) = fixtureServer("AdHocOperandTypes")

        assertEquals(
            "true",
            evaluateInFixture(server, uri, "Q.value = 5", typeMap = mapOf("Q" to "System.Quantity")) { h ->
                registerStackVar(h, "Q", Quantity().withValue(BigDecimal("5")))
            },
        )
        assertEquals(
            "true",
            evaluateInFixture(server, uri, "C.code = '8480-6'", typeMap = mapOf("C" to "System.Code")) { h ->
                registerStackVar(h, "C", Code().withCode("8480-6").withSystem("http://loinc.org"))
            },
        )
        assertEquals(
            "true",
            evaluateInFixture(server, uri, "CP.display = 'X'", typeMap = mapOf("CP" to "System.Concept")) { h ->
                registerStackVar(h, "CP", Concept().withCode(Code().withCode("8480-6")).withDisplay("X"))
            },
        )
        assertEquals(
            "true",
            evaluateInFixture(server, uri, "R.numerator.value = 5", typeMap = mapOf("R" to "System.Ratio")) { h ->
                registerStackVar(
                    h,
                    "R",
                    Ratio()
                        .withNumerator(Quantity().withValue(BigDecimal("5")))
                        .withDenominator(Quantity().withValue(BigDecimal("1")).withUnit("dL")),
                )
            },
        )
        assertEquals(
            "true",
            evaluateInFixture(server, uri, "IV.low = 1", typeMap = mapOf("IV" to "interval<System.Integer>")) { h ->
                registerStackVar(h, "IV", Interval(CqlInteger(1), true, CqlInteger(10), true))
            },
        )
        assertEquals(
            "true",
            evaluateInFixture(server, uri, "Count(DL) = 2", typeMap = mapOf("DL" to "list<list<System.Decimal>>")) { h ->
                registerStackVar(
                    h,
                    "DL",
                    CqlList(
                        listOf(
                            CqlList(listOf(CqlDecimal(BigDecimal("1.5")), CqlDecimal(BigDecimal("2.5")))),
                            CqlList(listOf(CqlDecimal(BigDecimal("3.5")), CqlDecimal(BigDecimal("4.5")))),
                        ),
                    ),
                )
            },
        )
    }

    @Test
    fun `G3 temporal aliases bind as real typed values`() {
        val (server, uri) = fixtureServer("AdHocOperandTypes")

        // A bare alias hit on the registry returns the real engine Date value. CqlDate.toString()
        // renders "@2024-01-15" (ELM date-literal style, no surrounding quotes) — engine-Value
        // rendering, NOT the stringified CqlString form that used to be stored (displayed as
        // `"@2024-01-15"` with formatVariableValue's String double-quoting).
        assertEquals(
            "@2024-01-15",
            evaluateInFixture(server, uri, "D") { h ->
                registerStackVar(h, "D", CqlDate("2024-01-15"))
            },
        )
        val dtValue =
            evaluateInFixture(server, uri, "DT") { h ->
                registerStackVar(h, "DT", CqlDateTime("2024-01-15T10:30:00.000", ZoneOffset.UTC))
            }
        assertTrue(dtValue.contains("2024-01-15"), dtValue)
        assertTrue(dtValue.contains("10:30"), dtValue)
        // Coalesce(D, ...) binds the real Date into the System.Date-typed parameter and returns it
        // unchanged; engine Values render via their own toString (Date → "@2024-01-15"). Previously
        // D was coerced to a CqlString (single-quoted "@2024-01-15") at registry write time, so the
        // Coalesce round-trip only "worked" coincidentally through string coercion; now it passes
        // the real Date through.
        val coalesced =
            evaluateInFixture(
                server,
                uri,
                "Coalesce(D, @2024-01-16)",
                typeMap = mapOf("D" to "System.Date"),
            ) { h ->
                registerStackVar(h, "D", CqlDate("2024-01-15"))
            }
        assertEquals("@2024-01-15", coalesced)

        // Real date semantics now evaluate directly on the alias — no longer available only through
        // accidental string coercion: component access (`year from` — note `.year` is not
        // parseable, `year` is a grammar reserved word), calendar-duration arithmetic, and
        // temporal containment.
        assertEquals(
            "true",
            evaluateInFixture(server, uri, "year from D = 2024", typeMap = mapOf("D" to "System.Date")) { h ->
                registerStackVar(h, "D", CqlDate("2024-01-15"))
            },
        )
        assertEquals(
            "@2024-01-16",
            evaluateInFixture(server, uri, "D + 1 days", typeMap = mapOf("D" to "System.Date")) { h ->
                registerStackVar(h, "D", CqlDate("2024-01-15"))
            },
        )
        assertEquals(
            "true",
            evaluateInFixture(
                server,
                uri,
                "D between @2024-01-01 and @2024-12-31",
                typeMap = mapOf("D" to "System.Date"),
            ) { h ->
                registerStackVar(h, "D", CqlDate("2024-01-15"))
            },
        )
    }

    @Test
    fun `G3 coerced-string operand hits PropertyEvaluator runtime throw not converter rejection`() {
        val (server, uri) = fixtureServer("AdHocOperandTypes")

        // System.Quantity aliases the coercion of a raw String. `.value` compiles (Quantity is a
        // ClassType in the system model), but the bound operand is a CqlString — PropertyEvaluator
        // has no String branch, so it throws IllegalArgumentException (surfaced as Runtime error),
        // NOT the "Cannot bind alias" converter rejection.
        val qResult =
            evaluateInFixture(
                server,
                uri,
                "Q.value",
                typeMap = mapOf("Q" to "System.Quantity"),
            ) { h ->
                registerStackVar(h, "Q", "a-coerced-string")
            }
        assertTrue(qResult.startsWith("Runtime error:"), qResult)
        assertTrue(qResult.contains("value"), qResult)
        assertFalse(qResult.startsWith("Runtime error: Cannot bind alias"), qResult)

        // Same for a Code-typed alias.
        val cResult =
            evaluateInFixture(
                server,
                uri,
                "C.code",
                typeMap = mapOf("C" to "System.Code"),
            ) { h ->
                registerStackVar(h, "C", "also-coerced")
            }
        assertTrue(cResult.startsWith("Runtime error:"), cResult)
        assertTrue(cResult.contains("code"), cResult)
        assertFalse(cResult.startsWith("Runtime error: Cannot bind alias"), cResult)
    }

    // ------------------------------------------------------------------ group 4

    @Test
    fun `G4 included-library alias resolves qualified translator type`() {
        val includingUri = URI("/org/opencds/cqf/cql/ls/server/AdHocIncludingLib.cql")
        val includedUri = URI("/org/opencds/cqf/cql/ls/server/AdHocIncludedLib.cql")

        val cs = TestContentService()
        val cm =
            CqlCompilationManager(
                cs,
                CompilerOptionsManager(cs),
                IgContextManager(cs),
                LibraryResolutionManager(emptyList()),
            )
        cm.compile(includingUri) // resolves the include through TestContentService
        cm.compile(includedUri)

        val client = mock(IDebugProtocolClient::class.java)
        val server = TestStreamingServer(cm, cs, mock(IgContextManager::class.java), mock(LibraryResolutionManager::class.java))
        server.connect(client)
        server.setLaunchUri(includingUri.toString())
        server.addLibrarySource("AdHocIncludedLib", includedUri)
        val modelManager = org.cqframework.cql.cql2elm.ModelManager()
        server.testHandler.libraryManager = org.cqframework.cql.cql2elm.LibraryManager(modelManager)

        val handler = server.testHandler
        val tupleType = "tuple{id:System.Integer,label:System.String}"

        // Qualified-key route: the translator resolved the alias in the included library's scope as
        // "AdHocIncludedLib.T" (qualified) while the stack variable is the bare "T".
        // resolveAdHocType now mirrors the qualified-then-bare pattern from the snapshot paths using
        // resolveFrameLibraryId() (the innermost frame is the included library), so the qualified
        // key resolves and the Tupled id evaluates.
        val qualified =
            evaluateIncluded(
                server,
                includedUri,
                "T.id > 0",
                handler,
                tupleType,
                qualified = true,
            )
        assertEquals("true", qualified)

        // Bare-key route: with the bare key the type resolves through the bare-name fallback and the
        // Tupled id evaluates identically. Both routes stay pinned — they exercise slightly
        // different lookup code paths, now both green.
        val positive =
            evaluateIncluded(
                server,
                includedUri,
                "T.id > 0",
                handler,
                tupleType,
                qualified = false,
            )
        assertEquals("true", positive)
    }

    private fun evaluateIncluded(
        server: TestStreamingServer,
        includedUri: URI,
        expression: String,
        handler: StreamingBreakpointHandler,
        tupleType: String,
        qualified: Boolean,
    ): String {
        val key = if (qualified) "AdHocIncludedLib.T" else "T"
        handler.variableTypeMap = mapOf(key to tupleType)
        handler.stepIn()
        val (_, state) = pauseState(server, includedUri)
        val elm =
            ExpressionDef().also {
                it.name = "Tuple Alias Query"
                it.locator = "5:1-5:10"
            }
        val engineThread = parkEngineThread(handler, elm, state)
        try {
            // Mirrors a real pause inside the included library: the call stack's innermost frame is
            // the included library, and the primary library id routes pause logic correctly.
            handler.lastPausedCallStack =
                listOf(StreamingBreakpointHandler.CallStackEntry(elm, null, "AdHocIncludedLib"))
            handler.primaryLibraryId = "AdHocIncludingLib"
            registerStackVar(
                handler,
                "T",
                Tuple().withElements(mutableMapOf("id" to CqlInteger(1), "label" to CqlString("one"))),
            )
            val response =
                server.evaluate(
                    EvaluateArguments().also {
                        it.expression = expression
                        it.context = "repl"
                        it.frameId = 0
                    },
                ).get(15, TimeUnit.SECONDS)
            assertNotNull(response.result)
            return response.result
        } finally {
            handler.release()
            engineThread.join(2000)
        }
    }

    // ------------------------------------------------------------------ group 5

    @Test
    fun `G5 ad-hoc eval times out with no parked engine thread`() {
        val (server, uri) = fixtureServer("AdHocQueryBasics")
        val handler = server.testHandler

        handler.stepIn()
        val (_, state) = pauseState(server, uri)
        val elm =
            ExpressionDef().also {
                it.name = "X"
                it.locator = "5:1-5:10"
            }
        // Full pause (asserting the PAUSE action) but deliberately NO parked engine thread, so the
        // submitted eval future is never drained and the 10s ad-hoc timeout fires -> "not available".
        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(elm, state))
        try {
            val start = System.nanoTime()
            val response =
                server.evaluate(
                    EvaluateArguments().also {
                        it.expression = "1 + 1"
                        it.context = "repl"
                        it.frameId = 0
                    },
                ).get(15, TimeUnit.SECONDS)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertEquals("not available", response.result)
            assertTrue(elapsedMs >= 9_000, "expected ~10s ad-hoc timeout, got ${elapsedMs}ms")
        } finally {
            handler.release()
        }
    }

    // ------------------------------------------------------------------ group 6

    @Test
    fun `G6 FHIR retrieves degrade at runtime but compile cleanly`() {
        val (server, uri) = fixtureServer("AdHocFhirQuery")

        // Baseline: plain arithmetic works in FHIR-model scope.
        assertEquals("3", evaluateInFixture(server, uri, "1 + 2"))

        // A retrieve on a data-provider-less environment compiles but fails at runtime.
        val retrieve =
            evaluateInFixture(server, uri, "exists (from [Encounter] E where E.status = 'finished')")
        assertTrue(retrieve.startsWith("Runtime error:"), retrieve)
        assertFalse(retrieve.startsWith("Compile error:"), retrieve)

        // Define ref wrapping a valueset retrieve — same shape.
        val define =
            evaluateInFixture(server, uri, "Count(\"Ambulatory Encounters\")")
        assertTrue(define.startsWith("Runtime error:"), define)
        assertFalse(define.startsWith("Compile error:"), define)

        // Empirical: `Quantity { value: 5.0, unit: 'mg' }` in `using FHIR` scope resolves to
        // FHIR.Quantity, whose `value` element is decimal-typed in a way the struct-literal
        // check rejects even for a System.Decimal literal ("Expected ... 'decimal', found
        // 'System.Decimal'"). Qualifying the type name forces System.Quantity, whose `value`
        // element is System.Decimal and matches exactly. Result content is asserted loosely.
        val quantity = evaluateInFixture(server, uri, "System.Quantity { value: 5.0, unit: 'mg' }")
        assertFalse(quantity.startsWith("Compile error:"), quantity)
        assertFalse(quantity.startsWith("Runtime error:"), quantity)
        assertTrue(quantity.contains("5"), quantity)
        assertTrue(quantity.contains("mg"), quantity)

        // A bare quantity literal in `using FHIR` scope ALSO resolves to FHIR.Quantity (default
        // model wins for bare types) — the ClassInstance re-encodes as `Quantity { unit: 'mg',
        // value: 5 }`. Content-checked rather than format-pinned.
        val bare = evaluateInFixture(server, uri, "5 'mg'")
        assertFalse(bare.startsWith("Compile error:"), bare)
        assertFalse(bare.startsWith("Runtime error:"), bare)
        assertTrue(bare.contains("5"), bare)
        assertTrue(bare.contains("mg"), bare)
    }

    @Test
    fun `G6 FHIR class-instance alias resolves property chain`() {
        val (server, uri) = fixtureServer("AdHocFhirQuery")

        val result =
            evaluateInFixture(
                server,
                uri,
                "Enc.id.value = 'e1'",
                typeMap = mapOf("Enc" to "FHIR.Encounter"),
            ) { h ->
                registerStackVar(h, "Enc", fhirEncounterClassInstance("e1"))
            }
        assertEquals("true", result)
    }

    // ------------------------------------------------------------------ group 7

    @Test
    fun `G7 extractIdentifiers tokens match lexical edge cases`() {
        val (server, _) = fixtureServer("AdHocQueryBasics")

        // Delimited identifiers keep their inner name, quotes stripped.
        assertEquals(listOf("My Define", "name"), extractIdentifiers(server, "\"My Define\".name"))
        // Single-quoted string literal contents are skipped entirely.
        assertEquals(listOf("Y"), extractIdentifiers(server, "'Items' + Y"))
        // Escaped quotes inside a delimited identifier round-trip.
        assertEquals(listOf("say \"hi\""), extractIdentifiers(server, "\"say \"\"hi\"\"\""))
        // Comments produce every word as a token.
        assertEquals(listOf("X", "comment", "Y"), extractIdentifiers(server, "X /* comment */ + Y"))
        // Keywords in a from...return query are ordinary identifiers here.
        assertEquals(listOf("from", "A", "X", "B", "Y", "return", "X"), extractIdentifiers(server, "from A X, B Y return X"))
        // Digits/operators are skipped unless they continue an identifier.
        assertEquals(listOf("X"), extractIdentifiers(server, "X + 123"))
        assertEquals(listOf("X", "A1"), extractIdentifiers(server, "X + A1"))
        // Underscore starts an identifier.
        assertEquals(listOf("_x", "Y"), extractIdentifiers(server, "_x + Y"))
        // An empty delimited identifier produces nothing.
        assertEquals(listOf("X"), extractIdentifiers(server, "\"\" + X"))
    }

    // ------------------------------------------------------------------ group 8

    @Test
    fun `G8 vital signs fixture compiles all idiom fragments and code equivalence evaluates`() {
        // fixtureServer() fails-fast if any of the 5 enriched idiom fragments fail to compile.
        val (server, uri) = fixtureServer("AdHocVitalSigns")

        // Data-free code equivalence using the fixture's code definitions (~ on System.Code). This
        // also proves the fixture's `C.code ~ "Systolic blood pressure"` equivalence compiles.
        assertEquals(
            "true",
            evaluateInFixture(
                server,
                uri,
                "exists (({ \"Systolic blood pressure\", \"Diastolic blood pressure\" }) C where C ~ \"Systolic blood pressure\")",
            ),
        )
        // Negative equivalence in the same shape.
        assertEquals(
            "false",
            evaluateInFixture(
                server,
                uri,
                "exists (({ \"Systolic blood pressure\" }) C where C ~ \"Diastolic blood pressure\")",
            ),
        )
    }

    @Test
    fun `G8 vital signs retrieval degrades at runtime`() {
        val (server, uri) = fixtureServer("AdHocVitalSigns")

        val reading = evaluateInFixture(server, uri, "Count(\"Blood Pressure Reading\")")
        assertTrue(reading.startsWith("Runtime error:"), reading)
        assertFalse(reading.startsWith("Compile error:"), reading)

        val status = evaluateInFixture(server, uri, "Count(\"BP Retrieve With Status\")")
        assertTrue(status.startsWith("Runtime error:"), status)
        assertFalse(status.startsWith("Compile error:"), status)
    }
}
