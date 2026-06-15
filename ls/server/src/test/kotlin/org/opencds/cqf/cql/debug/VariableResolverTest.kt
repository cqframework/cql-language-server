package org.opencds.cqf.cql.debug

import ca.uhn.fhir.context.BaseRuntimeChildDefinition
import ca.uhn.fhir.context.BaseRuntimeElementDefinition
import com.google.gson.Gson
import org.cqframework.cql.cql2elm.CqlCompiler
import org.cqframework.cql.cql2elm.LibraryManager
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.HumanName
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Period
import org.hl7.fhir.r4.model.StringType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService
import org.mockito.Mockito.`when` as whenever

class VariableResolverTest {
    companion object {
        private lateinit var compilationManager: CqlCompilationManager

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            val cs = TestContentService()
            compilationManager =
                CqlCompilationManager(
                    cs,
                    CompilerOptionsManager(cs),
                    IgContextManager(cs),
                    LibraryResolutionManager(emptyList()),
                )
        }
    }

    private val resolver = VariableResolver()
    private val gson = Gson()

    // -- formatVariableValue -------------------------------------------------

    @Nested
    inner class FormatVariableValue {
        @Test
        fun `null returns null string`() {
            assertEquals("null", resolver.formatVariableValue(null, gson))
        }

        @Test
        fun `String returns quoted value`() {
            assertEquals("\"hello\"", resolver.formatVariableValue("hello", gson))
        }

        @Test
        fun `Boolean returns true`() {
            assertEquals("true", resolver.formatVariableValue(true, gson))
        }

        @Test
        fun `Number returns string representation`() {
            assertEquals("42", resolver.formatVariableValue(42, gson))
        }

        @Test
        fun `IPrimitiveType returns getValueAsString`() {
            assertEquals("foo", resolver.formatVariableValue(StringType("foo"), gson))
        }

        @Test
        fun `IBase FHIR resource returns JSON`() {
            val patient = Patient()
            patient.id = "test-1"
            val result = resolver.formatVariableValue(patient, gson)
            assertTrue(result.startsWith("{"))
            assertTrue(result.endsWith("}"))
        }
    }

    // -- formatPropertyValue -------------------------------------------------

    @Nested
    inner class FormatPropertyValue {
        @Test
        fun `Period delegates to formatPeriodAsInterval`() {
            val period = Period()
            val result = resolver.formatPropertyValue(period, gson)
            assertEquals("[null, null)", result)
        }

        @Test
        fun `non-Period delegates to formatVariableValue`() {
            assertEquals("42", resolver.formatPropertyValue(42, gson))
        }
    }

    // -- formatPeriodAsInterval ---------------------------------------------

    @Nested
    inner class FormatPeriodAsInterval {
        @Test
        fun `with start and end`() {
            val period = Period()
            period.start = java.util.Date(1700000000000L)
            period.end = java.util.Date(1800000000000L)
            val result = resolver.formatPeriodAsInterval(period)
            assertTrue(result.startsWith("["))
            assertTrue(result.endsWith(")"))
        }

        @Test
        fun `null start and end`() {
            assertEquals("[null, null)", resolver.formatPeriodAsInterval(Period()))
        }
    }

    // -- isExpandable --------------------------------------------------------

    @Nested
    inner class IsExpandable {
        @Test
        fun `null returns false`() {
            assertFalse(resolver.isExpandable(null))
        }

        @Test
        fun `IPrimitiveType returns false`() {
            assertFalse(resolver.isExpandable(StringType("test")))
        }

        @Test
        fun `IBase returns true`() {
            assertTrue(resolver.isExpandable(Encounter()))
        }

        @Test
        fun `non-empty list returns true`() {
            assertTrue(resolver.isExpandable(listOf(Encounter())))
        }

        @Test
        fun `empty list returns false`() {
            assertFalse(resolver.isExpandable(emptyList<Any>()))
        }

        @Test
        fun `plain string returns false`() {
            assertFalse(resolver.isExpandable("hello"))
        }
    }

    // -- registerIfExpandable ------------------------------------------------

    @Nested
    inner class RegisterIfExpandable {
        @Test
        fun `null returns 0`() {
            assertEquals(0, resolver.registerIfExpandable(null))
        }

        @Test
        fun `IBase returns positive ref`() {
            val ref = resolver.registerIfExpandable(Encounter(), "Encounter")
            assertTrue(ref > 0)
        }

        @Test
        fun `without typeName stores ref`() {
            val ref = resolver.registerIfExpandable(Encounter())
            assertTrue(ref > 0)
        }

        @Test
        fun `successive calls increase ref`() {
            val r1 = resolver.registerIfExpandable(Encounter())
            val r2 = resolver.registerIfExpandable(Encounter())
            assertTrue(r2 > r1)
        }

        @Test
        fun `with typeName stores in varRefTypes`() {
            val ref = resolver.registerIfExpandable(Encounter(), "Encounter")
            assertEquals("Encounter", resolver.varRefTypes[ref])
        }
    }

    // -- childrenOf ----------------------------------------------------------

    @Nested
    inner class ChildrenOf {
        @Test
        fun `IPrimitiveType returns empty`() {
            assertTrue(resolver.childrenOf(StringType("x")).isEmpty())
        }

        @Test
        fun `Patient returns child variables`() {
            val patient = Patient()
            patient.addName(HumanName().setFamily("Smith"))
            val children = resolver.childrenOf(patient)
            assertTrue(children.isNotEmpty())
            val nameChild = children.firstOrNull { it.name == "name" }
            assertNotNull(nameChild)
        }

        @Test
        fun `non-empty list returns indexed entries`() {
            val children = resolver.childrenOf(listOf("a", "b"))
            assertEquals(2, children.size)
            assertEquals("[0]", children[0].name)
            assertEquals("[1]", children[1].name)
        }

        @Test
        fun `empty list returns empty`() {
            assertTrue(resolver.childrenOf(emptyList<Any>()).isEmpty())
        }

        @Test
        fun `non-FHIR non-list returns empty`() {
            assertTrue(resolver.childrenOf("plain string").isEmpty())
        }
    }

    // -- findInVarRefs -------------------------------------------------------

    @Nested
    inner class FindInVarRefs {
        @Test
        fun `matching name returns response`() {
            val patient = Patient()
            patient.addName(HumanName().setFamily("Smith"))
            val patientRef = resolver.registerIfExpandable(patient)
            val children = resolver.childrenOf(patient)
            val firstChild = children.firstOrNull() ?: return
            val result = resolver.findInVarRefs(firstChild.name)
            assertNotNull(result)
            assertEquals(firstChild.value, result!!.result)
        }

        @Test
        fun `no match returns null`() {
            assertNull(resolver.findInVarRefs("nonExistentChild"))
        }
    }

    // -- extractPropertyValue ------------------------------------------------

    @Nested
    inner class ExtractPropertyValue {
        @Test
        fun `known property returns value`() {
            val patient = Patient()
            patient.id = "test-id"
            val value = resolver.extractPropertyValue(patient, "id")
            assertNotNull(value)
        }

        @Test
        fun `unknown property returns null`() {
            val patient = Patient()
            assertNull(resolver.extractPropertyValue(patient, "nonExistentProperty"))
        }
    }

    // -- getResourceId -------------------------------------------------------

    @Nested
    inner class GetResourceId {
        @Test
        fun `resource with ID returns id part`() {
            val patient = Patient()
            patient.id = "test-id"
            assertEquals("test-id", resolver.getResourceId(patient))
        }

        @Test
        fun `resource without ID returns unknown`() {
            assertEquals("unknown", resolver.getResourceId(Patient()))
        }
    }

    // -- getFhirContextForVersion --------------------------------------------

    @Nested
    inner class GetFhirContextForVersion {
        @Test
        fun `null returns default FhirContext`() {
            val ctx = resolver.getFhirContextForVersion(null)
            assertNotNull(ctx)
            val r4 = ca.uhn.fhir.context.FhirContext.forR4()
            assertEquals(r4.javaClass, ctx.javaClass)
        }

        @Test
        fun `DSTU3 returns DSTU3 context`() {
            val ctx = resolver.getFhirContextForVersion("DSTU3")
            val expected = ca.uhn.fhir.context.FhirContext.forDstu3()
            assertEquals(expected.javaClass, ctx.javaClass)
        }

        @Test
        fun `R5 returns R5 context`() {
            val ctx = resolver.getFhirContextForVersion("R5")
            val expected = ca.uhn.fhir.context.FhirContext.forR5()
            assertEquals(expected.javaClass, ctx.javaClass)
        }
    }

    // -- unwrapListType ------------------------------------------------------

    @Nested
    inner class UnwrapListType {
        @Test
        fun `list prefix stripped`() {
            assertEquals("Foo", resolver.unwrapListType("list<Foo>"))
        }

        @Test
        fun `no change if not list`() {
            assertEquals("Foo", resolver.unwrapListType("Foo"))
        }
    }

    // -- parseLocatorLines ---------------------------------------------------

    @Nested
    inner class ParseLocatorLines {
        @Test
        fun `null returns all zeros`() {
            val bounds = resolver.parseLocatorLines(null)
            assertEquals(LocatorBounds(0, 0, 0, 0), bounds)
        }

        @Test
        fun `valid locator returns 0-indexed bounds`() {
            val bounds = resolver.parseLocatorLines("10:12-10:24")
            assertEquals(LocatorBounds(9, 11, 9, 24), bounds)
        }

        @Test
        fun `invalid locator returns zeros`() {
            val bounds = resolver.parseLocatorLines("invalid")
            assertEquals(LocatorBounds(0, 0, 0, 0), bounds)
        }
    }

    // -- extractExpressionName -----------------------------------------------

    @Nested
    inner class ExtractExpressionName {
        @Test
        fun `null returns null`() {
            assertNull(resolver.extractExpressionName(null))
        }
    }

    // -- notAvailable --------------------------------------------------------

    @Test
    fun `notAvailable returns expected response`() {
        val resp = resolver.notAvailable()
        assertEquals("not available", resp.result)
        assertEquals(0, resp.variablesReference)
    }

    // -- resetVarRefs --------------------------------------------------------

    @Test
    fun `resetVarRefs clears maps and resets counter`() {
        resolver.registerIfExpandable(Encounter())
        resolver.registerIfExpandable(Patient())
        assertTrue(resolver.varRefs.isNotEmpty())
        assertTrue(resolver.nextVarRef > 1000)

        resolver.resetVarRefs()

        assertTrue(resolver.varRefs.isEmpty())
        assertTrue(resolver.varRefTypes.isEmpty())
        assertEquals(1000, resolver.nextVarRef)
    }

    // -- unwrapListType additional tests -------------------------------------

    @Nested
    inner class UnwrapListTypeAdditional {
        @Test
        fun `list prefix is case insensitive`() {
            assertEquals("Foo", resolver.unwrapListType("LIST<Foo>"))
        }

        @Test
        fun `list with spaces is trimmed`() {
            assertEquals("Foo", resolver.unwrapListType("list< Foo >"))
        }
    }

    // -- buildVariableTypeMap ------------------------------------------------

    @Nested
    inner class BuildVariableTypeMap {
        @Test
        fun `null compiler returns empty map`() {
            val map = resolver.buildVariableTypeMap(null)
            assertTrue(map.isEmpty())
        }

        @Test
        fun `One_cql has defined expressions in type map`() {
            val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
            val compiler = compilationManager.compile(uri) ?: return
            val map = resolver.buildVariableTypeMap(compiler)
            assertTrue(map.isNotEmpty(), "One.cql should produce type map entries, got: $map")
        }

        @Test
        fun `CoverageFixture1_cql has expression types in type map`() {
            val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/CoverageFixture1.cql")!!
            val compiler = compilationManager.compile(uri) ?: return
            val map = resolver.buildVariableTypeMap(compiler)
            assertTrue(map.isNotEmpty(), "CoverageFixture1 should produce type map entries")
        }

        @Test
        fun `CoverageFixture2_cql alias types are collected`() {
            val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/CoverageFixture2.cql")!!
            val compiler = compilationManager.compile(uri) ?: return
            val map = resolver.buildVariableTypeMap(compiler)
            assertTrue(map.isNotEmpty(), "CoverageFixture2 should produce type map entries")
        }

        @Test
        fun `CoverageFixture4_cql processes if expression`() {
            val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/CoverageFixture4.cql")!!
            val compiler = compilationManager.compile(uri) ?: return
            val map = resolver.buildVariableTypeMap(compiler)
            assertTrue(map.containsKey("IfVal"), "IfVal should be in type map")
        }

        @Test
        fun `CoverageFixture4_cql processes binary expression`() {
            val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/CoverageFixture4.cql")!!
            val compiler = compilationManager.compile(uri) ?: return
            val map = resolver.buildVariableTypeMap(compiler)
            assertTrue(map.containsKey("BinaryVal"), "BinaryVal should be in type map")
        }

        @Test
        fun `CoverageFixture4_cql processes and expression`() {
            val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/CoverageFixture4.cql")!!
            val compiler = compilationManager.compile(uri) ?: return
            val map = resolver.buildVariableTypeMap(compiler)
            assertTrue(map.containsKey("AndVal"), "AndVal should be in type map")
        }
    }

    // -- profileChildrenOf ----------------------------------------------------

    /**
     * Tests for [VariableResolver.profileChildrenOf]. This method is 0% covered
     * (26/26 branches missed). These tests exercise the early-return branches.
     * The success path with real ClassType requires complex FHIR model infrastructure
     * that is better covered via integration tests.
     */
    @Nested
    inner class ProfileChildrenOf {
        private fun mockChild(name: String): BaseRuntimeChildDefinition {
            val child = mock(BaseRuntimeChildDefinition::class.java)
            whenever(child.elementName).thenReturn(name)
            return child
        }

        private fun mockElementDef(children: List<BaseRuntimeChildDefinition>?): BaseRuntimeElementDefinition<*> {
            val def = mock(BaseRuntimeElementDefinition::class.java)
            whenever(def.children).thenReturn(children)
            return def
        }

        @Test
        fun `null launchCompiler returns elementDef children`() {
            val children = listOf(mockChild("field1"), mockChild("field2"))
            val elementDef = mockElementDef(children)

            val result = resolver.profileChildrenOf("SomeType", elementDef, null)

            assertEquals(children, result)
        }

        @Test
        fun `null libraryManager returns elementDef children`() {
            val children = listOf(mockChild("field1"), mockChild("field2"))
            val elementDef = mockElementDef(children)

            val compiler = mock(CqlCompiler::class.java)
            whenever(compiler.libraryManager).thenReturn(null)

            val result = resolver.profileChildrenOf("SomeType", elementDef, compiler)

            assertEquals(children, result)
        }

        @Test
        fun `null modelManager returns elementDef children`() {
            val children = listOf(mockChild("field1"), mockChild("field2"))
            val elementDef = mockElementDef(children)

            val compiler = mock(CqlCompiler::class.java)
            val libMgr = mock(LibraryManager::class.java)
            whenever(compiler.libraryManager).thenReturn(libMgr)
            whenever(libMgr.modelManager).thenReturn(null)

            val result = resolver.profileChildrenOf("SomeType", elementDef, compiler)

            assertEquals(children, result)
        }

        @Test
        fun `elementDef children is null returns empty list`() {
            val elementDef = mockElementDef(null)

            val result = resolver.profileChildrenOf("SomeType", elementDef, null)

            assertTrue(result.isEmpty())
        }
    }
}
