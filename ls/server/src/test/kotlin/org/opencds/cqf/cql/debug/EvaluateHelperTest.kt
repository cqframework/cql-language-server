package org.opencds.cqf.cql.debug

import org.eclipse.lsp4j.Range
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Period
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.opencds.cqf.cql.engine.execution.State
import org.opencds.cqf.cql.ls.server.provider.CursorCategory

class EvaluateHelperTest {
    private lateinit var helper: EvaluateHelper
    private lateinit var variableResolver: VariableResolver

    @BeforeEach
    fun setUp() {
        variableResolver = VariableResolver()
        helper = EvaluateHelper(variableResolver, null)
    }

    // -- nameMatches --------------------------------------------------------

    @Nested
    inner class NameMatches {
        @Test
        fun `exact string match`() {
            assert(helper.nameMatches("Patient", "Patient"))
        }

        @Test
        fun `quoted expression matches`() {
            assert(helper.nameMatches("Patient", "\"Patient\""))
        }

        @Test
        fun `multi-word snapshot matches one word`() {
            assert(helper.nameMatches("FHIR Patient", "Patient"))
        }

        @Test
        fun `no match returns false`() {
            assert(!helper.nameMatches("Patient", "Encounter"))
        }
    }

    // -- parseHoverPosition -------------------------------------------------

    @Nested
    inner class ParseHoverPosition {
        @Test
        fun `valid position returns pair`() {
            val result = helper.parseHoverPosition("@10:5")
            assertNotNull(result)
            assertEquals(10, result!!.first)
            assertEquals(5, result.second)
        }

        @Test
        fun `no at-sign still parses`() {
            val result = helper.parseHoverPosition("10:5")
            assertNotNull(result)
            assertEquals(10, result!!.first)
        }

        @Test
        fun `non-numeric returns null`() {
            assertNull(helper.parseHoverPosition("@abc:def"))
        }

        @Test
        fun `only one number returns null`() {
            assertNull(helper.parseHoverPosition("@10"))
        }

        @Test
        fun `at-sign with colon but no column number returns null`() {
            assertNull(helper.parseHoverPosition("@10:"))
        }

        @Test
        fun `no at-sign with partial colon returns null`() {
            assertNull(helper.parseHoverPosition("10"))
        }
    }

    // -- splitParameterName -------------------------------------------------

    @Nested
    inner class SplitParameterName {
        @Test
        fun `dotted name splits correctly`() {
            val (lib, param) = helper.splitParameterName("FHIRHelpers.TestParam")
            assertEquals("FHIRHelpers", lib)
            assertEquals("TestParam", param)
        }

        @Test
        fun `no dot returns global`() {
            val (lib, param) = helper.splitParameterName("TestParam")
            assertEquals("(Global)", lib)
            assertEquals("TestParam", param)
        }
    }

    // -- findParameterMetadata ----------------------------------------------

    @Nested
    inner class FindParameterMetadata {
        @Test
        fun `matching library and name returns metadata`() {
            val meta =
                mapOf(
                    "TestLib" to
                        listOf(
                            CqlDebugServer.ParameterMetadata("Param1", "System.String", null),
                            CqlDebugServer.ParameterMetadata("Param2", "System.Integer", "0"),
                        ),
                )
            val result = helper.findParameterMetadata("TestLib", "Param1", meta)
            assertNotNull(result)
            assertEquals("Param1", result!!.name)
            assertEquals("System.String", result.type)
        }

        @Test
        fun `unknown library returns null`() {
            val result = helper.findParameterMetadata("Unknown", "Param1", emptyMap())
            assertNull(result)
        }

        @Test
        fun `unknown name returns null`() {
            val meta = mapOf("TestLib" to listOf(CqlDebugServer.ParameterMetadata("Param1", "System.String", null)))
            assertNull(helper.findParameterMetadata("TestLib", "Param999", meta))
        }
    }

    // -- findLaunchParameterType --------------------------------------------

    @Nested
    inner class FindLaunchParameterType {
        @Test
        fun `matching parameter returns type`() {
            val params = listOf(ParameterRequestData("Param1", "System.String", "\"val\""))
            assertEquals("System.String", helper.findLaunchParameterType("Param1", params))
        }

        @Test
        fun `no match returns null`() {
            assertNull(helper.findLaunchParameterType("Param1", emptyList()))
        }

        @Test
        fun `null parameters returns null`() {
            assertNull(helper.findLaunchParameterType("Param1", null))
        }
    }

    // -- extractExpressionName ----------------------------------------------

    @Nested
    inner class ExtractExpressionName {
        @Test
        fun `null returns null`() {
            assertNull(helper.extractExpressionName(null))
        }

        @Test
        fun `ExpressionDef returns name`() {
            val exprDef = org.hl7.elm.r1.ExpressionDef().apply { name = "MyDefine" }
            assertEquals("MyDefine", helper.extractExpressionName(exprDef))
        }

        @Test
        fun `FunctionDef returns name`() {
            val funcDef = org.hl7.elm.r1.FunctionDef().apply { name = "MyFunc" }
            assertEquals("MyFunc", helper.extractExpressionName(funcDef))
        }
    }

    // -- lookupByName -------------------------------------------------------

    @Nested
    inner class LookupByName {
        @Test
        fun `matching snapshot returns value`() {
            val snapshots =
                listOf(
                    ExpressionSnapshot("Patient", "Patient {name: \"x\"}", "src/main.cql", 1, 0, 1, 10),
                    ExpressionSnapshot("Encounter", "Encounter {id: \"y\"}", "src/main.cql", 2, 0, 2, 8),
                )
            val result = helper.lookupByName("Patient", null, snapshots, 1)
            assertNotNull(result)
            assertEquals("Patient {name: \"x\"}", result.result)
        }

        @Test
        fun `no match returns not available`() {
            val result = helper.lookupByName("Missing", null, emptyList(), 0)
            assertEquals("not available", result.result)
            assertEquals(0, result.variablesReference)
        }

        @Test
        fun `frameId limits search scope`() {
            val snapshots =
                listOf(
                    ExpressionSnapshot("A", "1", "src/main.cql", 1, 0, 1, 1),
                    ExpressionSnapshot("B", "2", "src/main.cql", 2, 0, 2, 1),
                    ExpressionSnapshot("A", "3", "src/main.cql", 3, 0, 3, 1),
                )
            val result = helper.lookupByName("A", 1, snapshots, 2)
            assertEquals("1", result.result)
        }
    }

    // -- handleHoverEvaluate ------------------------------------------------

    @Nested
    inner class HandleHoverEvaluate {
        @Test
        fun `no match returns not available`() {
            val result = helper.handleHoverEvaluate("Missing", null, emptyList(), emptyList())
            assertEquals("not available", result.result)
        }

        @Test
        fun `matching define snapshot returns value`() {
            val snapshots =
                listOf(
                    ExpressionSnapshot("Patient", "Patient {name: \"x\"}", "src/main.cql", 1, 0, 1, 10),
                )
            val result = helper.handleHoverEvaluate("Patient", null, snapshots, emptyList())
            assertEquals("Patient {name: \"x\"}", result.result)
        }

        @Test
        fun `position-based hover with matching subexpression`() {
            val pos = helper.parseHoverPosition("@1:5")
            assertNotNull(pos)
            val snapshots =
                listOf(
                    ExpressionSnapshot("Patient", "Patient {name: \"x\"}", "src/main.cql", 1, 0, 1, 10),
                )
            val subSnapshots =
                listOf(
                    SubExpressionSnapshot("\"x\"", "Patient", 1, 5, 1, 8),
                    SubExpressionSnapshot("Patient", "Patient", 1, 0, 1, 10),
                )
            val result = helper.handleHoverEvaluate("@1:5", 0, snapshots, subSnapshots)
            assertEquals("\"x\"", result.result)
        }

        @Test
        fun `position-based hover with no subexpression match`() {
            val snapshots =
                listOf(
                    ExpressionSnapshot("Patient", "Patient {name: \"x\"}", "src/main.cql", 1, 0, 1, 10),
                )
            val result = helper.handleHoverEvaluate("@100:100", 0, snapshots, emptyList())
            assertEquals("not available", result.result)
        }
    }

    // -- resolveFromCursorCategory -----------------------------------------

    @Nested
    inner class ResolveFromCursorCategory {
        private lateinit var handler: StreamingBreakpointHandler
        private lateinit var state: State
        private val gson = com.google.gson.Gson()
        private val testRange = Range(org.eclipse.lsp4j.Position(0, 0), org.eclipse.lsp4j.Position(0, 5))

        @BeforeEach
        fun setUpStreaming() {
            handler = StreamingBreakpointHandler()
            state = mock(State::class.java)
        }

        @Test
        fun `AliasReference found returns evaluate response`() {
            handler.runtimeRegistry.putDefine("MyAlias", 42, null, null)
            val category = CursorCategory.AliasReference("MyAlias", testRange)
            val result = helper.resolveFromCursorCategory(category, state, handler, gson)
            assertNotNull(result)
            assertEquals("42", result!!.result)
        }

        @Test
        fun `AliasReference not found returns null`() {
            val category = CursorCategory.AliasReference("Unknown", testRange)
            val result = helper.resolveFromCursorCategory(category, state, handler, gson)
            assertNull(result)
        }

        @Test
        fun `OperandRef found returns evaluate response`() {
            handler.runtimeRegistry.putStackVariable("Operand1", 42, null)
            val category = CursorCategory.OperandRef("Operand1", testRange)
            val result = helper.resolveFromCursorCategory(category, state, handler, gson)
            assertNotNull(result)
            assertEquals("42", result!!.result)
        }

        @Test
        fun `OperandRef not found returns null`() {
            val category = CursorCategory.OperandRef("UnknownOperand", testRange)
            val result = helper.resolveFromCursorCategory(category, state, handler, gson)
            assertNull(result)
        }

        @Test
        fun `ExpressionRef without library returns evaluate response`() {
            handler.runtimeRegistry.putDefine("MyDefine", 42, null, null)
            val category = CursorCategory.ExpressionRef("MyDefine", null, testRange)
            val result = helper.resolveFromCursorCategory(category, state, handler, gson)
            assertNotNull(result)
            assertEquals("42", result!!.result)
        }

        @Test
        fun `ExpressionRef with library not found returns null`() {
            val category = CursorCategory.ExpressionRef("MyDefine", "MyLib", testRange)
            val result = helper.resolveFromCursorCategory(category, state, handler, gson)
            assertNull(result)
        }

        @Test
        fun `ExpressionRef not found returns null`() {
            val category = CursorCategory.ExpressionRef("UnknownDefine", null, testRange)
            val result = helper.resolveFromCursorCategory(category, state, handler, gson)
            assertNull(result)
        }

        @Test
        fun `ParameterRef not found returns null`() {
            val category = CursorCategory.ParameterRef("UnknownParam", null, testRange)
            val result = helper.resolveFromCursorCategory(category, state, handler, gson)
            assertNull(result)
        }

        @Test
        fun `PropertyName with alias resolves property from alias`() {
            val patient = org.hl7.fhir.r4.model.Patient()
            patient.id = "patient-1"
            handler.runtimeRegistry.putDefine("P", patient, null, null)
            val category = CursorCategory.PropertyName("resourceType", "P", testRange)
            val result = helper.resolveFromCursorCategory(category, state, handler, gson)
            assertNotNull(result)
            assertEquals("\"Patient\"", result!!.result)
        }

        @Test
        fun `PropertyName without alias returns null`() {
            val category = CursorCategory.PropertyName("someProperty", null, testRange)
            val result = helper.resolveFromCursorCategory(category, state, handler, gson)
            assertNull(result)
        }

        @Test
        fun `unhandled category returns null`() {
            val category = CursorCategory.FunctionCall("SomeFunc", null, null, null)
            val result = helper.resolveFromCursorCategory(category, state, handler, gson)
            assertNull(result)
        }
    }

    // -- evaluateStreaming -------------------------------------------------

    @Nested
    inner class EvaluateStreaming {
        private lateinit var handler: StreamingBreakpointHandler
        private lateinit var state: State
        private val gson = com.google.gson.Gson()

        @BeforeEach
        fun setUpStreaming() {
            handler = StreamingBreakpointHandler()
            state = mock(State::class.java)
        }

        @Test
        fun `registry hit returns cached value`() {
            handler.runtimeRegistry.putDefine("FoundVar", 42, null, null)
            val result =
                helper.evaluateStreaming(
                    "FoundVar",
                    state,
                    handler,
                    gson,
                    null,
                    emptyMap(),
                    null,
                    emptyMap(),
                )
            assertEquals("42", result.result)
            assertEquals(0, result.variablesReference)
        }

        @Test
        fun `registry miss on identifier reports doesn't exist`() {
            val result =
                helper.evaluateStreaming(
                    "NonExistent",
                    state,
                    handler,
                    gson,
                    null,
                    emptyMap(),
                    null,
                    emptyMap(),
                )
            assertEquals("Identifier doesn't exist (CQL is case-sensitive)", result.result)
        }

        @Test
        fun `registry miss on case-mismatched defined name suggests corrected name`() {
            handler.runtimeRegistry.putDefine("IndexPCP", "enc", "FHIR.Encounter", null)
            val result =
                helper.evaluateStreaming(
                    "indexPCP",
                    state,
                    handler,
                    gson,
                    null,
                    emptyMap(),
                    null,
                    emptyMap(),
                )
            assertEquals("CQL identifiers are case-sensitive; did you mean IndexPCP?", result.result)
        }

        @Test
        fun `quoted identifier resolves exactly by inner name`() {
            handler.runtimeRegistry.putDefine("IndexPCP", 42, "System.Integer", null)
            val result =
                helper.evaluateStreaming(
                    "\"IndexPCP\"",
                    state,
                    handler,
                    gson,
                    null,
                    emptyMap(),
                    null,
                    emptyMap(),
                )
            assertEquals("42", result.result)
        }

        @Test
        fun `quoted case-mismatched identifier suggests with quotes preserved`() {
            handler.runtimeRegistry.putDefine("IndexPCP", "enc", "FHIR.Encounter", null)
            val result =
                helper.evaluateStreaming(
                    "\"indexPCP\"",
                    state,
                    handler,
                    gson,
                    null,
                    emptyMap(),
                    null,
                    emptyMap(),
                )
            assertEquals("CQL identifiers are case-sensitive; did you mean \"IndexPCP\"?", result.result)
        }

        @Test
        fun `delimited case-mismatched identifier suggests with backticks preserved`() {
            handler.runtimeRegistry.putDefine("IndexPCP", "enc", "FHIR.Encounter", null)
            val result =
                helper.evaluateStreaming(
                    "`indexPCP`",
                    state,
                    handler,
                    gson,
                    null,
                    emptyMap(),
                    null,
                    emptyMap(),
                )
            assertEquals("CQL identifiers are case-sensitive; did you mean `IndexPCP`?", result.result)
        }

        @Test
        fun `dotted miss on exact root suggests case-correct property name`() {
            val encounter = Encounter()
            encounter.id = "enc-1"
            encounter.period =
                Period().also {
                    it.start = java.util.Date(1700000000000L)
                    it.end = java.util.Date(1800000000000L)
                }
            handler.runtimeRegistry.putDefine("IndexPCP", encounter, "FHIR.Encounter", null)
            val result =
                helper.evaluateStreaming(
                    "IndexPCP.pERiod",
                    state,
                    handler,
                    gson,
                    null,
                    emptyMap(),
                    null,
                    emptyMap(),
                )
            assertEquals(
                "CQL identifiers are case-sensitive; did you mean IndexPCP.period?",
                result.result,
            )
        }

        @Test
        fun `dotted miss on exact root with no matching property keeps not available`() {
            handler.runtimeRegistry.putDefine("IndexPCP", Encounter().also { it.id = "enc-1" }, "FHIR.Encounter", null)
            val result =
                helper.evaluateStreaming(
                    "IndexPCP.nonexistentProp",
                    state,
                    handler,
                    gson,
                    null,
                    emptyMap(),
                    null,
                    emptyMap(),
                )
            assertEquals("not available", result.result)
        }

        @Test
        fun `at-expression with position finds value via findValueAtPosition`() {
            handler.runtimeRegistry.putDefine("SomeExpr", "atValue", null, null)
            val result =
                helper.evaluateStreaming(
                    "@10:5",
                    state,
                    handler,
                    gson,
                    null,
                    emptyMap(),
                    null,
                    emptyMap(),
                )
            assertEquals("not available", result.result)
        }

        @Test
        fun `expression resolves via findInVarRefs`() {
            val result =
                helper.evaluateStreaming(
                    "SomeVarRef",
                    state,
                    handler,
                    gson,
                    null,
                    emptyMap(),
                    null,
                    emptyMap(),
                )
            assertEquals("Identifier doesn't exist (CQL is case-sensitive)", result.result)
        }
    }

    // -- resolvePropertyValue ---------------------------------------------

    @Nested
    inner class ResolvePropertyValue {
        private lateinit var handler: StreamingBreakpointHandler
        private val gson = com.google.gson.Gson()

        @BeforeEach
        fun setUp() {
            handler = StreamingBreakpointHandler()
        }

        @Test
        fun `source is not ExpressionRef returns null`() {
            val property = org.hl7.elm.r1.Property().apply { path = "active" }
            val result = helper.resolvePropertyValue(property, handler, gson)
            assertNull(result)
        }

        @Test
        fun `source not in registry returns null`() {
            val prop =
                org.hl7.elm.r1.Property().apply {
                    path = "active"
                    source = org.hl7.elm.r1.ExpressionRef().apply { name = "Unknown" }
                }
            val result = helper.resolvePropertyValue(prop, handler, gson)
            assertNull(result)
        }

        @Test
        fun `IBase source returns formatted property value`() {
            val patient = org.hl7.fhir.r4.model.Patient()
            patient.id = "patient-1"
            handler.runtimeRegistry.putDefine("MyPat", patient, null, null)
            val prop =
                org.hl7.elm.r1.Property().apply {
                    path = "resourceType"
                    source = org.hl7.elm.r1.ExpressionRef().apply { name = "MyPat" }
                }
            val result = helper.resolvePropertyValue(prop, handler, gson)
            assertNotNull(result)
            assertEquals("\"Patient\"", result!!.first)
        }

        @Test
        fun `List source returns formatted property values for each item`() {
            val patient1 = org.hl7.fhir.r4.model.Patient()
            patient1.id = "p1"
            val patient2 = org.hl7.fhir.r4.model.Patient()
            patient2.id = "p2"
            @Suppress("UNCHECKED_CAST")
            val list = listOf<Any>(patient1, patient2) as List<Any>
            handler.runtimeRegistry.putDefine("MyPatList", list, null, null)
            val prop =
                org.hl7.elm.r1.Property().apply {
                    path = "id"
                    source = org.hl7.elm.r1.ExpressionRef().apply { name = "MyPatList" }
                }
            val result = helper.resolvePropertyValue(prop, handler, gson)
            assertNotNull(result)
            assertTrue(result!!.first.startsWith("["))
            assertTrue(result.first.contains("p1"))
            assertTrue(result.first.contains("p2"))
        }

        @Test
        fun `null sourceRef name returns null`() {
            val prop =
                org.hl7.elm.r1.Property().apply {
                    path = "active"
                    source = org.hl7.elm.r1.ExpressionRef().apply { name = null }
                }
            val result = helper.resolvePropertyValue(prop, handler, gson)
            assertNull(result)
        }
    }

    // -- resolvePropertyFromAlias -----------------------------------------

    @Nested
    inner class ResolvePropertyFromAlias {
        private lateinit var handler: StreamingBreakpointHandler
        private val gson = com.google.gson.Gson()

        @BeforeEach
        fun setUp() {
            handler = StreamingBreakpointHandler()
        }

        @Test
        fun `alias not in registry returns null`() {
            val result = helper.resolvePropertyFromAlias("UnknownAlias", "active", handler, gson)
            assertNull(result)
        }

        @Test
        fun `IBase alias returns formatted property value`() {
            val patient = org.hl7.fhir.r4.model.Patient()
            patient.id = "patient-1"
            handler.runtimeRegistry.putDefine("P", patient, null, null)
            val result = helper.resolvePropertyFromAlias("P", "resourceType", handler, gson)
            assertNotNull(result)
            assertEquals("\"Patient\"", result!!.first)
        }

        @Test
        fun `List alias returns formatted property values for each item`() {
            val patient1 = org.hl7.fhir.r4.model.Patient()
            patient1.id = "p1"
            val patient2 = org.hl7.fhir.r4.model.Patient()
            patient2.id = "p2"
            @Suppress("UNCHECKED_CAST")
            val list = listOf<Any>(patient1, patient2) as List<Any>
            handler.runtimeRegistry.putDefine("Patients", list, null, null)
            val result = helper.resolvePropertyFromAlias("Patients", "id", handler, gson)
            assertNotNull(result)
            assertTrue(result!!.first.startsWith("["))
            assertTrue(result.first.contains("p1"))
            assertTrue(result.first.contains("p2"))
        }

        @Test
        fun `List alias with missing property returns null`() {
            val patient1 = org.hl7.fhir.r4.model.Patient()
            patient1.id = "p1"
            val patient2 = org.hl7.fhir.r4.model.Patient()
            patient2.id = "p2"
            @Suppress("UNCHECKED_CAST")
            val list = listOf<Any>(patient1, patient2) as List<Any>
            handler.runtimeRegistry.putDefine("Patients", list, null, null)
            val result = helper.resolvePropertyFromAlias("Patients", "nonexistentProperty", handler, gson)
            assertNull(result)
        }
    }

    // -- resolveDottedExpression -----------------------------------------

    @Nested
    inner class ResolveDottedExpression {
        private lateinit var handler: StreamingBreakpointHandler
        private val gson = com.google.gson.Gson()

        @BeforeEach
        fun setUp() {
            handler = StreamingBreakpointHandler()
        }

        @Test
        fun `registered IBase with property path resolves and formats`() {
            val encounter = org.hl7.fhir.r4.model.Encounter()
            encounter.period =
                org.hl7.fhir.r4.model.Period().also {
                    it.start = java.util.Date(1700000000000L)
                    it.end = java.util.Date(1800000000000L)
                }
            handler.runtimeRegistry.putDefine("IndexPCP", encounter, null, null)
            val result = helper.resolveDottedExpression("IndexPCP.period", handler.runtimeRegistry, gson)
            assertNotNull(result)
            assertTrue(result!!.result.startsWith("["))
            assertTrue(result.result.endsWith(")"))
            assertTrue(result.variablesReference > 0)
        }

        @Test
        fun `multi-hop path resolves nested element`() {
            val encounter = org.hl7.fhir.r4.model.Encounter()
            encounter.period =
                org.hl7.fhir.r4.model.Period().also {
                    it.start = java.util.Date(1700000000000L)
                }
            handler.runtimeRegistry.putDefine("IndexPCP", encounter, null, null)
            val result = helper.resolveDottedExpression("IndexPCP.period.start", handler.runtimeRegistry, gson)
            assertNotNull(result)
            assertTrue(result!!.result.contains("2023"))
        }

        @Test
        fun `list root property maps over items`() {
            val p1 = org.hl7.fhir.r4.model.Patient()
            p1.id = "p1"
            val p2 = org.hl7.fhir.r4.model.Patient()
            p2.id = "p2"
            @Suppress("UNCHECKED_CAST")
            val list = listOf<Any>(p1, p2) as List<Any>
            handler.runtimeRegistry.putDefine("Patients", list, null, null)
            val result = helper.resolveDottedExpression("Patients.id", handler.runtimeRegistry, gson)
            assertNotNull(result)
            assertTrue(result!!.result.contains("p1"))
            assertTrue(result.result.contains("p2"))
        }

        @Test
        fun `list root with index resolves item property`() {
            val p1 = org.hl7.fhir.r4.model.Patient()
            p1.id = "p1"
            val p2 = org.hl7.fhir.r4.model.Patient()
            p2.id = "p2"
            @Suppress("UNCHECKED_CAST")
            val list = listOf<Any>(p1, p2) as List<Any>
            handler.runtimeRegistry.putDefine("Patients", list, null, null)
            val result = helper.resolveDottedExpression("Patients[1].id", handler.runtimeRegistry, gson)
            assertNotNull(result)
            assertTrue(result!!.result.contains("p2"))
        }

        @Test
        fun `interval root with boundary property resolves`() {
            val interval =
                org.opencds.cqf.cql.engine.runtime.Interval(
                    org.opencds.cqf.cql.engine.runtime.Integer(1),
                    true,
                    org.opencds.cqf.cql.engine.runtime.Integer(10),
                    false,
                )
            handler.runtimeRegistry.putDefine("Idx", interval, null, null)
            val result = helper.resolveDottedExpression("Idx.low", handler.runtimeRegistry, gson)
            assertNotNull(result)
            assertEquals("1", result!!.result)
        }

        @Test
        fun `non-dotted expression returns null`() {
            handler.runtimeRegistry.putDefine("IndexPCP", org.hl7.fhir.r4.model.Encounter(), null, null)
            assertNull(helper.resolveDottedExpression("IndexPCP", handler.runtimeRegistry, gson))
        }

        @Test
        fun `at-sign expression returns null`() {
            assertNull(helper.resolveDottedExpression("@12:3", handler.runtimeRegistry, gson))
        }

        @Test
        fun `unknown root returns null`() {
            val result = helper.resolveDottedExpression("Unknown.period", handler.runtimeRegistry, gson)
            assertNull(result)
        }

        @Test
        fun `unknown property returns null`() {
            handler.runtimeRegistry.putDefine("IndexPCP", org.hl7.fhir.r4.model.Encounter(), null, null)
            assertNull(helper.resolveDottedExpression("IndexPCP.nonexistentProperty", handler.runtimeRegistry, gson))
        }
    }
}
