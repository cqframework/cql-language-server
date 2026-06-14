package org.opencds.cqf.cql.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

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
}
