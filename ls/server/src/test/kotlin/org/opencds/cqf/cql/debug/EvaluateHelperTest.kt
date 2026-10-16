package org.opencds.cqf.cql.debug

import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.ModelManager
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
            val result = helper.resolveFromCursorCategory(category, state, handler)
            assertNotNull(result)
            assertEquals("42", result!!.result)
        }

        @Test
        fun `AliasReference not found returns null`() {
            val category = CursorCategory.AliasReference("Unknown", testRange)
            val result = helper.resolveFromCursorCategory(category, state, handler)
            assertNull(result)
        }

        @Test
        fun `OperandRef found returns evaluate response`() {
            handler.runtimeRegistry.putStackVariable("Operand1", 42, null)
            val category = CursorCategory.OperandRef("Operand1", testRange)
            val result = helper.resolveFromCursorCategory(category, state, handler)
            assertNotNull(result)
            assertEquals("42", result!!.result)
        }

        @Test
        fun `OperandRef not found returns null`() {
            val category = CursorCategory.OperandRef("UnknownOperand", testRange)
            val result = helper.resolveFromCursorCategory(category, state, handler)
            assertNull(result)
        }

        @Test
        fun `ExpressionRef without library returns evaluate response`() {
            handler.runtimeRegistry.putDefine("MyDefine", 42, null, null)
            val category = CursorCategory.ExpressionRef("MyDefine", null, testRange)
            val result = helper.resolveFromCursorCategory(category, state, handler)
            assertNotNull(result)
            assertEquals("42", result!!.result)
        }

        @Test
        fun `ExpressionRef with library not found returns null`() {
            val category = CursorCategory.ExpressionRef("MyDefine", "MyLib", testRange)
            val result = helper.resolveFromCursorCategory(category, state, handler)
            assertNull(result)
        }

        @Test
        fun `ExpressionRef not found returns null`() {
            val category = CursorCategory.ExpressionRef("UnknownDefine", null, testRange)
            val result = helper.resolveFromCursorCategory(category, state, handler)
            assertNull(result)
        }

        @Test
        fun `ParameterRef not found returns null`() {
            val category = CursorCategory.ParameterRef("UnknownParam", null, testRange)
            val result = helper.resolveFromCursorCategory(category, state, handler)
            assertNull(result)
        }

        @Test
        fun `PropertyName with alias resolves property from alias`() {
            val patient = org.hl7.fhir.r4.model.Patient()
            patient.id = "patient-1"
            handler.runtimeRegistry.putDefine("P", patient, null, null)
            val category = CursorCategory.PropertyName("resourceType", "P", testRange)
            val result = helper.resolveFromCursorCategory(category, state, handler)
            assertNotNull(result)
            assertEquals("\"Patient\"", result!!.result)
        }

        @Test
        fun `PropertyName without alias returns null`() {
            val category = CursorCategory.PropertyName("someProperty", null, testRange)
            val result = helper.resolveFromCursorCategory(category, state, handler)
            assertNull(result)
        }

        @Test
        fun `unhandled category returns null`() {
            val category = CursorCategory.FunctionCall("SomeFunc", null, null, null)
            val result = helper.resolveFromCursorCategory(category, state, handler)
            assertNull(result)
        }
    }

    // -- evaluateStreaming -------------------------------------------------

    @Nested
    inner class EvaluateStreaming {
        private lateinit var handler: StreamingBreakpointHandler
        private lateinit var state: State

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

        @BeforeEach
        fun setUp() {
            handler = StreamingBreakpointHandler()
        }

        @Test
        fun `source is not ExpressionRef returns null`() {
            val property = org.hl7.elm.r1.Property().apply { path = "active" }
            val result = helper.resolvePropertyValue(property, handler)
            assertNull(result)
        }

        @Test
        fun `source not in registry returns null`() {
            val prop =
                org.hl7.elm.r1.Property().apply {
                    path = "active"
                    source = org.hl7.elm.r1.ExpressionRef().apply { name = "Unknown" }
                }
            val result = helper.resolvePropertyValue(prop, handler)
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
            val result = helper.resolvePropertyValue(prop, handler)
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
            val result = helper.resolvePropertyValue(prop, handler)
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
            val result = helper.resolvePropertyValue(prop, handler)
            assertNull(result)
        }
    }

    // -- resolvePropertyFromAlias -----------------------------------------

    @Nested
    inner class ResolvePropertyFromAlias {
        private lateinit var handler: StreamingBreakpointHandler

        @BeforeEach
        fun setUp() {
            handler = StreamingBreakpointHandler()
        }

        @Test
        fun `alias not in registry returns null`() {
            val result = helper.resolvePropertyFromAlias("UnknownAlias", "active", handler)
            assertNull(result)
        }

        @Test
        fun `IBase alias returns formatted property value`() {
            val patient = org.hl7.fhir.r4.model.Patient()
            patient.id = "patient-1"
            handler.runtimeRegistry.putDefine("P", patient, null, null)
            val result = helper.resolvePropertyFromAlias("P", "resourceType", handler)
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
            val result = helper.resolvePropertyFromAlias("Patients", "id", handler)
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
            val result = helper.resolvePropertyFromAlias("Patients", "nonexistentProperty", handler)
            assertNull(result)
        }
    }

    // -- resolveDottedExpression -----------------------------------------

    @Nested
    inner class ResolveDottedExpression {
        private lateinit var handler: StreamingBreakpointHandler

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
            val result = helper.resolveDottedExpression("IndexPCP.period", handler.runtimeRegistry)
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
            val result = helper.resolveDottedExpression("IndexPCP.period.start", handler.runtimeRegistry)
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
            val result = helper.resolveDottedExpression("Patients.id", handler.runtimeRegistry)
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
            val result = helper.resolveDottedExpression("Patients[1].id", handler.runtimeRegistry)
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
            val result = helper.resolveDottedExpression("Idx.low", handler.runtimeRegistry)
            assertNotNull(result)
            assertEquals("1", result!!.result)
        }

        @Test
        fun `non-dotted expression returns null`() {
            handler.runtimeRegistry.putDefine("IndexPCP", org.hl7.fhir.r4.model.Encounter(), null, null)
            assertNull(helper.resolveDottedExpression("IndexPCP", handler.runtimeRegistry))
        }

        @Test
        fun `at-sign expression returns null`() {
            assertNull(helper.resolveDottedExpression("@12:3", handler.runtimeRegistry))
        }

        @Test
        fun `unknown root returns null`() {
            val result = helper.resolveDottedExpression("Unknown.period", handler.runtimeRegistry)
            assertNull(result)
        }

        @Test
        fun `unknown property returns null`() {
            handler.runtimeRegistry.putDefine("IndexPCP", org.hl7.fhir.r4.model.Encounter(), null, null)
            assertNull(helper.resolveDottedExpression("IndexPCP.nonexistentProperty", handler.runtimeRegistry))
        }
    }

    // -- evaluateAdHocExpression ----------------------------------------------

    @Nested
    inner class EvaluateAdHocExpression {
        private lateinit var libraryManager: LibraryManager

        @BeforeEach
        fun setUpLibraryManager() {
            libraryManager = LibraryManager(ModelManager())
        }

        @Test
        fun `simple comparison returns a function def wrapping the expression`() {
            val sourceText = "library TestLib\n\ndefine \"X\": 1 + 1\n"
            val (fn, error) = helper.evaluateAdHocExpression("1 + 1", sourceText, libraryManager)
            assertNotNull(fn)
            assertNull(error)
            assertTrue(fn is org.hl7.elm.r1.FunctionDef)
            assertTrue(fn!!.name == "__debugEval__")
        }

        @Test
        fun `expression referencing existing define compiles`() {
            val sourceText =
                """
                library TestLib

                define "IndexPCP": 42

                define "Other": 10
                """.trimIndent()
            val (fn, error) = helper.evaluateAdHocExpression("IndexPCP + Other", sourceText, libraryManager)
            assertNotNull(fn)
            assertNull(error)
        }

        @Test
        fun `invalid CQL returns compile error`() {
            val sourceText = "library TestLib\n\n"
            // An unterminated string literal should cause a parse error
            val (fn, error) = helper.evaluateAdHocExpression("\"unterminated", sourceText, libraryManager)
            assertNull(fn)
            assertNotNull(error)
            assertTrue(error!!.result.startsWith("Compile error:"))
        }

        @Test
        fun `unresolved name returns compile error`() {
            val sourceText = "library TestLib\n\n"
            // referencing a nonexistent define
            val (fn, error) = helper.evaluateAdHocExpression("NonexistentDefine", sourceText, libraryManager)
            assertNull(fn)
            assertNotNull(error)
            assertTrue(error!!.result.startsWith("Compile error:"))
        }

        @Test
        fun `synthetic function wraps expression correctly`() {
            val sourceText = "library TestLib\n\n"
            val (fn, _) = helper.evaluateAdHocExpression("true", sourceText, libraryManager)
            assertNotNull(fn)
            // With no aliases a zero-argument function is emitted whose body is a Literal("true")
            assertTrue(fn!!.operand.isEmpty())
            assertTrue(fn.expression is org.hl7.elm.r1.Literal)
        }

/** Renders an ELM [TypeSpecifier] as a `(namespaceURI, localPart)` name for type assertions. */
        private fun typeQName(spec: org.hl7.elm.r1.TypeSpecifier?): Pair<String, String> {
            val n =
                when (spec) {
                    is org.hl7.elm.r1.NamedTypeSpecifier -> spec.name
                    is org.hl7.elm.r1.IntervalTypeSpecifier ->
                        (spec.pointType as? org.hl7.elm.r1.NamedTypeSpecifier)?.name
                    else -> null
                }
            return (n?.namespaceURI ?: "") to (n?.localPart ?: "")
        }

        @Test
        fun `query alias is emitted as a typed function parameter`() {
            val sourceText = "library TestLib\n\n"
            val (fn, error) =
                helper.evaluateAdHocExpression(
                    "V.value",
                    sourceText,
                    libraryManager,
                    listOf("V" to "System.Quantity"),
                )
            assertNotNull(fn)
            assertNull(error)
            assertEquals(1, fn!!.operand.size)
            assertEquals("V", fn.operand[0].name)
            // The param is a System.namespaced type (prefix `System` is dropped in ELM).
            val (ns, localPart) = typeQName(fn.operand[0].operandTypeSpecifier)
            assertEquals("urn:hl7-org:elm-types:r1", ns)
            assertEquals("Quantity", localPart)
            assertTrue(fn.expression is org.hl7.elm.r1.Property)
        }

        @Test
        fun `interval type string is normalized for the parameter declaration`() {
            val sourceText = "library TestLib\n\n"
            val (fn, error) =
                helper.evaluateAdHocExpression(
                    "I.low",
                    sourceText,
                    libraryManager,
                    listOf("I" to "interval<System.DateTime>"),
                )
            assertNotNull(fn)
            assertNull(error)
            // normalizeType re-cased `interval<...>` -> `Interval<...>` so it compiles to an
            // Interval<System.DateTime> parameter (elicited via its point type's System namespace).
            assertTrue(fn!!.operand[0].operandTypeSpecifier is org.hl7.elm.r1.IntervalTypeSpecifier)
            val (ns, localPart) = typeQName(fn.operand[0].operandTypeSpecifier)
            assertEquals("urn:hl7-org:elm-types:r1", ns)
            assertEquals("DateTime", localPart)
        }

        @Test
        fun `list type string is normalized for the parameter declaration`() {
            val sourceText = "library TestLib\n\n"
            val (fn, error) =
                helper.evaluateAdHocExpression(
                    "L.value",
                    sourceText,
                    libraryManager,
                    listOf("L" to "list<System.Quantity>"),
                )
            assertNotNull(fn)
            assertNull(error)
            val spec = fn!!.operand[0].operandTypeSpecifier
            assertTrue(spec is org.hl7.elm.r1.ListTypeSpecifier)
            val element = (spec as org.hl7.elm.r1.ListTypeSpecifier).elementType
            assertTrue(element is org.hl7.elm.r1.NamedTypeSpecifier)
            val (ns, localPart) = typeQName(element)
            assertEquals("urn:hl7-org:elm-types:r1", ns)
            assertEquals("Quantity", localPart)
        }

        @Test
        fun `nested generic type strings are fully normalized`() {
            val sourceText = "library TestLib\n\n"
            // `list<interval<...>>` would previously become `List<interval<...>>` (inner generic
            // left lowercase), which the grammar rejects -- a nested generic must be fully
            // re-cased to `List<Interval<...>>`.
            val (fn, error) =
                helper.evaluateAdHocExpression(
                    "I",
                    sourceText,
                    libraryManager,
                    listOf("I" to "list<interval<System.DateTime>>"),
                )
            assertNotNull(fn, "expected success but got: ${error?.result}")
            val spec = fn!!.operand[0].operandTypeSpecifier
            assertTrue(spec is org.hl7.elm.r1.ListTypeSpecifier)
            val interval = (spec as org.hl7.elm.r1.ListTypeSpecifier).elementType
            assertTrue(interval is org.hl7.elm.r1.IntervalTypeSpecifier)
            val point = (interval as org.hl7.elm.r1.IntervalTypeSpecifier).pointType
            val (ns, localPart) = typeQName(point)
            assertEquals("urn:hl7-org:elm-types:r1", ns)
            assertEquals("DateTime", localPart)
        }

        @Test
        fun `choice type string is normalized for the parameter declaration`() {
            val sourceText = "library TestLib\n\n"
            val (fn, error) =
                helper.evaluateAdHocExpression(
                    "C",
                    sourceText,
                    libraryManager,
                    listOf("C" to "choice<System.Integer,System.String>"),
                )
            assertNotNull(fn)
            assertNull(error)
            val spec = fn!!.operand[0].operandTypeSpecifier
            assertTrue(spec is org.hl7.elm.r1.ChoiceTypeSpecifier)
            val choice = (spec as org.hl7.elm.r1.ChoiceTypeSpecifier).choice
            assertEquals(2, choice.size)
            assertTrue(choice[0] is org.hl7.elm.r1.NamedTypeSpecifier)
            assertTrue(choice[1] is org.hl7.elm.r1.NamedTypeSpecifier)
        }

        @Test
        fun `tuple type string is normalized for the parameter declaration`() {
            val sourceText = "library TestLib\n\n"
            // TupleTypeElement.toString emits `name:Type` WITH a colon; cql.g4 tupleElementDefinition
            // is `referentialIdentifier typeSpecifier` (no colon), so normalizeType must strip it.
            val (fn, error) =
                helper.evaluateAdHocExpression(
                    "T",
                    sourceText,
                    libraryManager,
                    listOf("T" to "tuple{id:System.String}"),
                )
            assertNotNull(fn, "expected success but got: ${error?.result}")
            val spec = fn!!.operand[0].operandTypeSpecifier
            assertTrue(spec is org.hl7.elm.r1.TupleTypeSpecifier)
            val tuple = (spec as org.hl7.elm.r1.TupleTypeSpecifier).element
            assertEquals(1, tuple.size)
            assertEquals("id", tuple[0].name)
            assertTrue(tuple[0].elementType is org.hl7.elm.r1.NamedTypeSpecifier)
        }

        @Test
        fun `choice of tuple types is normalized for the parameter declaration`() {
            val sourceText = "library TestLib\n\n"
            // Mirrors a real measure query alias (e.g. CMS190 "NoVTEMedication"): a Choice of tuple
            // types with nested list and nested choice element types, colon-delimited as emitted by
            // TupleTypeElement.toString. Before normalizeType stripped tuple element colons this
            // failed to compile with "Syntax error at :".
            val type =
                "choice<tuple{id:System.String,values:list<System.Integer>,authoredOn:choice<System.DateTime,System.Date>}," +
                    "tuple{id:System.String,values:list<System.Integer>,authoredOn:System.Date}>"
            val (fn, error) =
                helper.evaluateAdHocExpression(
                    "C",
                    sourceText,
                    libraryManager,
                    listOf("C" to type),
                )
            assertNotNull(fn, "expected success but got: ${error?.result}")
            val spec = fn!!.operand[0].operandTypeSpecifier
            assertTrue(spec is org.hl7.elm.r1.ChoiceTypeSpecifier)
            val choices = (spec as org.hl7.elm.r1.ChoiceTypeSpecifier).choice
            assertEquals(2, choices.size)
            choices.forEach { c ->
                assertTrue(c is org.hl7.elm.r1.TupleTypeSpecifier)
                val elements = (c as org.hl7.elm.r1.TupleTypeSpecifier).element
                assertEquals(listOf("id", "values", "authoredOn"), elements.map { it.name })
                assertTrue(elements[1].elementType is org.hl7.elm.r1.ListTypeSpecifier)
                assertNotNull(elements[2].elementType)
            }
        }

        @Test
        fun `nested tuple types are normalized for the parameter declaration`() {
            val sourceText = "library TestLib\n\n"
            val (fn, error) =
                helper.evaluateAdHocExpression(
                    "T",
                    sourceText,
                    libraryManager,
                    listOf("T" to "tuple{outer:tuple{id:System.String}}"),
                )
            assertNotNull(fn, "expected success but got: ${error?.result}")
            val spec = fn!!.operand[0].operandTypeSpecifier
            assertTrue(spec is org.hl7.elm.r1.TupleTypeSpecifier)
            val outer = (spec as org.hl7.elm.r1.TupleTypeSpecifier).element
            assertEquals(1, outer.size)
            assertEquals("outer", outer[0].name)
            val inner = outer[0].elementType
            assertTrue(inner is org.hl7.elm.r1.TupleTypeSpecifier)
            val innerElements = (inner as org.hl7.elm.r1.TupleTypeSpecifier).element
            assertEquals(1, innerElements.size)
            assertEquals("id", innerElements[0].name)
            assertTrue(innerElements[0].elementType is org.hl7.elm.r1.NamedTypeSpecifier)
        }

        @Test
        fun `two aliases preserve declaration order matching the argument order`() {
            val sourceText = "library TestLib\n\n"
            val (fn, error) =
                helper.evaluateAdHocExpression(
                    "X + Y",
                    sourceText,
                    libraryManager,
                    listOf("X" to "System.Integer", "Y" to "System.Integer"),
                )
            assertNotNull(fn)
            assertNull(error)
            assertEquals(listOf("X", "Y"), fn!!.operand.map { it.name })
        }
    }
}
