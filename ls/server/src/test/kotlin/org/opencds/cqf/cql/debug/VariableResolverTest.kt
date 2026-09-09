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
import org.opencds.cqf.cql.engine.fhir.fhirModelNamespaceUri
import org.opencds.cqf.cql.engine.runtime.ClassInstance
import org.opencds.cqf.cql.engine.runtime.Code
import org.opencds.cqf.cql.engine.runtime.Interval
import org.opencds.cqf.cql.engine.runtime.Tuple
import org.opencds.cqf.cql.engine.runtime.Value
import org.opencds.cqf.cql.engine.util.offsetDateTimeParse
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService
import javax.xml.namespace.QName
import org.mockito.Mockito.`when` as whenever
import org.opencds.cqf.cql.engine.runtime.Integer as CqlInteger
import org.opencds.cqf.cql.engine.runtime.List as CqlList
import org.opencds.cqf.cql.engine.runtime.String as CqlString

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

    /**
     * Builds a [ClassInstance] shaped the way the CQL engine actually represents a retrieved FHIR
     * resource at runtime (see [org.opencds.cqf.fhir.cql.ClassInstanceHelper]) — NOT a raw HAPI
     * object. A resource's "id" is itself a nested ClassInstance of type "id" with a "value" element.
     */
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
        return ClassInstance(QName(fhirModelNamespaceUri, type), values.toMutableMap())
    }

    private fun fhirEncounterClassInstance(id: String): ClassInstance =
        fhirClassInstance("Encounter", mapOf("id" to fhirClassInstance("id", mapOf("value" to id))))

    /**
     * Builds a FHIR `Period` [ClassInstance] shaped the way the CQL engine represents a FHIR
     * composite element at runtime: `start`/`end` are themselves FHIR `dateTime` ClassInstances
     * whose `value` element carries a real engine [org.opencds.cqf.cql.engine.runtime.DateTime].
     */
    private fun fhirDateTimeElement(value: String): ClassInstance =
        fhirClassInstance(
            "dateTime",
            mapOf("value" to org.opencds.cqf.cql.engine.runtime.DateTime(offsetDateTimeParse(value))),
        )

    private fun fhirPeriodClassInstance(
        start: String?,
        end: String?,
    ): ClassInstance {
        val elements = mutableMapOf<String, Any>()
        if (start != null) elements["start"] = fhirDateTimeElement(start)
        if (end != null) elements["end"] = fhirDateTimeElement(end)
        return fhirClassInstance("Period", elements)
    }

    // -- normalizeValue --------------------------------------------------------

    @Nested
    inner class NormalizeValue {
        @Test
        fun `ClassInstance for a FHIR resource converts to a real FHIR resource`() {
            val result = resolver.normalizeValue(fhirEncounterClassInstance("enc-1"))
            assertTrue(result is Encounter)
            assertEquals("enc-1", (result as Encounter).idElement.idPart)
        }

        @Test
        fun `ClassInstance outside the FHIR namespace is left unchanged`() {
            val nonFhir = ClassInstance(QName("urn:not-fhir", "Foo"), mutableMapOf("bar" to CqlInteger(1)))
            assertEquals(nonFhir, resolver.normalizeValue(nonFhir))
        }

        @Test
        fun `CqlList of FHIR ClassInstances converts to a list of real FHIR resources`() {
            val cqlList = CqlList(listOf(fhirEncounterClassInstance("enc-1"), fhirEncounterClassInstance("enc-2")))
            val result = resolver.normalizeValue(cqlList)
            assertTrue(result is List<*>)
            val list = result as List<*>
            assertEquals(2, list.size)
            assertEquals("enc-1", (list[0] as Encounter).idElement.idPart)
            assertEquals("enc-2", (list[1] as Encounter).idElement.idPart)
        }

        @Test
        fun `ClassInstance for a FHIR composite element converts to a real FHIR object`() {
            val result = resolver.normalizeValue(fhirPeriodClassInstance("2026-11-02T11:00:00.000+00:00", null))
            assertTrue(result is Period)
            assertEquals("2026-11-02T11:00:00.000Z", (result as Period).startElement.valueAsString)
            assertNull(result.endElement.value)
        }

        @Test
        fun `non-CQL value passes through unchanged`() {
            assertEquals("plain", resolver.normalizeValue("plain"))
            assertNull(resolver.normalizeValue(null))
        }
    }

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

        @Test
        fun `Interval formats as closed range`() {
            val interval = Interval(CqlInteger(1), true, CqlInteger(10), true)
            assertEquals("[1, 10]", resolver.formatVariableValue(interval, gson))
        }

        @Test
        fun `Interval formats open boundary`() {
            val interval = Interval(CqlInteger(1), false, CqlInteger(10), false)
            assertEquals("(1, 10)", resolver.formatVariableValue(interval, gson))
        }

        @Test
        fun `StructuredValue formats as type with fields`() {
            val tuple = Tuple().withElements(mutableMapOf("id" to CqlInteger(1)))
            assertEquals("Tuple { id: 1 }", resolver.formatVariableValue(tuple, gson))
        }

        @Test
        fun `List of FHIR resources returns resource-list summary`() {
            val encounter1 = Encounter().also { it.id = "enc-1" }
            val encounter2 = Encounter().also { it.id = "enc-2" }
            val result = resolver.formatVariableValue(listOf(encounter1, encounter2), gson)
            assertEquals("[Encounter/enc-1, Encounter/enc-2]", result)
        }

        @Test
        fun `CqlList of ClassInstance FHIR resources returns resource-list summary, matching real CQL Retrieve output`() {
            val cqlList = CqlList(listOf(fhirEncounterClassInstance("enc-1"), fhirEncounterClassInstance("enc-2")))
            assertEquals("[Encounter/enc-1, Encounter/enc-2]", resolver.formatVariableValue(cqlList, gson))
        }

        @Test
        fun `single ClassInstance FHIR resource returns full FHIR JSON, not a structured-value dump`() {
            val result = resolver.formatVariableValue(fhirEncounterClassInstance("enc-1"), gson)
            assertTrue(result.startsWith("{"), "expected FHIR JSON, got: $result")
            assertTrue(result.contains("\"enc-1\""))
        }

        @Test
        fun `ClassInstance FHIR composite element returns FHIR JSON, not a structured-value dump`() {
            val result = resolver.formatVariableValue(fhirPeriodClassInstance("2026-11-02T11:00:00.000+00:00", null), gson)
            assertTrue(result.startsWith("{"), "expected FHIR JSON, got: $result")
            assertTrue(result.contains("\"start\""))
            assertFalse(result.contains("ClassInstance"))
        }

        @Test
        fun `empty list returns bracket string`() {
            assertEquals("[]", resolver.formatVariableValue(emptyList<Any>(), gson))
        }

        @Test
        fun `non-resource list falls back to gson`() {
            assertEquals("[\"a\",\"b\"]", resolver.formatVariableValue(listOf("a", "b"), gson))
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
        fun `FHIR Period ClassInstance renders as interval with start and null end`() {
            val result =
                resolver.formatPropertyValue(
                    fhirPeriodClassInstance("2026-11-02T11:00:00.000+00:00", null),
                    gson,
                )
            assertEquals("[2026-11-02T11:00:00.000Z, null)", result)
        }

        @Test
        fun `FHIR Period ClassInstance renders as interval with start and end`() {
            val result =
                resolver.formatPropertyValue(
                    fhirPeriodClassInstance("2026-11-02T11:00:00.000+00:00", "2026-11-02T12:00:00.000+00:00"),
                    gson,
                )
            assertEquals("[2026-11-02T11:00:00.000Z, 2026-11-02T12:00:00.000Z)", result)
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

        @Test
        fun `Interval returns true`() {
            assertTrue(resolver.isExpandable(Interval(CqlInteger(1), true, CqlInteger(10), true)))
        }

        @Test
        fun `StructuredValue returns true`() {
            assertTrue(resolver.isExpandable(Tuple()))
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
        fun `list of FHIR resources returns ResourceType-id named entries`() {
            val encounter1 = Encounter().also { it.id = "enc-1" }
            val encounter2 = Encounter().also { it.id = "enc-2" }
            val children = resolver.childrenOf(listOf(encounter1, encounter2))
            assertEquals(2, children.size)
            assertEquals("Encounter/enc-1", children[0].name)
            assertEquals("Encounter/enc-2", children[1].name)
            assertEquals("Encounter", children[0].type)
        }

        @Test
        fun `CqlList of ClassInstance FHIR resources returns ResourceType-id named entries, matching real CQL Retrieve output`() {
            val cqlList = CqlList(listOf(fhirEncounterClassInstance("enc-1"), fhirEncounterClassInstance("enc-2")))
            val children = resolver.childrenOf(cqlList)
            assertEquals(2, children.size)
            assertEquals("Encounter/enc-1", children[0].name)
            assertEquals("Encounter/enc-2", children[1].name)
            assertEquals("Encounter", children[0].type)
        }

        @Test
        fun `mixed list of resource and non-resource keeps index naming for non-resource items`() {
            val encounter = Encounter().also { it.id = "enc-1" }
            val children = resolver.childrenOf(listOf(encounter, "plain-value"))
            assertEquals("Encounter/enc-1", children[0].name)
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

        @Test
        fun `Interval returns low, lowClosed, high, highClosed`() {
            val interval = Interval(CqlInteger(1), true, CqlInteger(10), false)
            val children = resolver.childrenOf(interval)
            assertEquals(4, children.size)
            assertEquals("1", children.first { it.name == "low" }.value)
            assertEquals("true", children.first { it.name == "lowClosed" }.value)
            assertEquals("10", children.first { it.name == "high" }.value)
            assertEquals("false", children.first { it.name == "highClosed" }.value)
        }

        @Test
        fun `StructuredValue returns elements as children`() {
            val code = Code().withCode("123").withSystem("http://example.com").withDisplay("Example")
            val children = resolver.childrenOf(code)
            assertEquals(4, children.size)
            assertTrue(children.any { it.name == "code" })
            assertTrue(children.any { it.name == "system" })
            assertTrue(children.any { it.name == "display" })
        }
    }

    // -- buildResourceVariable / formatResourceList ---------------------------

    @Nested
    inner class BuildResourceVariable {
        @Test
        fun `uses ResourceType-id as default name`() {
            val patient = Patient().also { it.id = "pat-1" }
            val variable = resolver.buildResourceVariable(patient, gson)
            assertEquals("Patient/pat-1", variable.name)
            assertEquals("Patient", variable.type)
            assertTrue(variable.variablesReference > 0)
        }

        @Test
        fun `displayNameOverride wins over default naming`() {
            val patient = Patient().also { it.id = "pat-1" }
            val variable = resolver.buildResourceVariable(patient, gson, "custom-name")
            assertEquals("custom-name", variable.name)
        }

        @Test
        fun `value is full FHIR JSON`() {
            val patient = Patient().also { it.id = "pat-1" }
            val variable = resolver.buildResourceVariable(patient, gson)
            assertTrue(variable.value.startsWith("{"))
            assertTrue(variable.value.endsWith("}"))
        }
    }

    @Nested
    inner class FormatResourceList {
        @Test
        fun `empty list returns brackets`() {
            assertEquals("[]", resolver.formatResourceList(emptyList()))
        }

        @Test
        fun `non-empty list joins ResourceType-id entries`() {
            val encounter1 = Encounter().also { it.id = "enc-1" }
            val encounter2 = Encounter().also { it.id = "enc-2" }
            assertEquals("[Encounter/enc-1, Encounter/enc-2]", resolver.formatResourceList(listOf(encounter1, encounter2)))
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

        @Test
        fun `iterating while children register new refs does not throw ConcurrentModificationException`() {
            val patient1 = Patient().also { it.addName(HumanName().setFamily("Smith")) }
            val patient2 = Patient().also { it.addName(HumanName().setFamily("Jones")) }
            resolver.registerIfExpandable(patient1)
            resolver.registerIfExpandable(patient2)
            // Forces the loop to expand every registered parent (each expansion registers
            // new child refs into the same map being iterated) without finding a match.
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

    // -- readProperty / navigatePropertyPath ---------------------------------

    @Nested
    inner class NavigatePropertyPath {
        private fun encounterWithPeriod(): Encounter =
            Encounter().also {
                it.period =
                    Period().also { p ->
                        p.start = java.util.Date(1700000000000L)
                        p.end = java.util.Date(1800000000000L)
                    }
            }

        @Test
        fun `fhir resource single hop resolves element`() {
            val value = resolver.navigatePropertyPath(encounterWithPeriod(), listOf("period"))
            assertTrue(value is Period)
        }

        @Test
        fun `multi-hop resolves nested element`() {
            val value = resolver.navigatePropertyPath(encounterWithPeriod(), listOf("period", "start"))
            assertNotNull(value)
            assertTrue(value is org.hl7.fhir.r4.model.DateTimeType)
        }

        @Test
        fun `List root with index resolves item`() {
            val list = listOf(Encounter().also { it.id = "enc-1" }, Encounter().also { it.id = "enc-2" })
            val value = resolver.navigatePropertyPath(list, listOf("[1]"))
            assertTrue(value is Encounter)
            assertEquals("enc-2", (value as Encounter).idElement.idPart)
        }

        @Test
        fun `IBase with indexed property resolves list item`() {
            val encounter = Encounter().also { it.addReasonCode(org.hl7.fhir.r4.model.CodeableConcept()) }
            val value = resolver.navigatePropertyPath(encounter, listOf("reasonCode[0]"))
            assertTrue(value is org.hl7.fhir.r4.model.CodeableConcept)
        }

        @Test
        fun `Interval boundaries resolve`() {
            val interval = Interval(CqlInteger(1), true, CqlInteger(10), false)
            assertEquals(CqlInteger(1), resolver.navigatePropertyPath(interval, listOf("low")))
            assertEquals(CqlInteger(10), resolver.navigatePropertyPath(interval, listOf("high")))
            assertEquals(true, resolver.navigatePropertyPath(interval, listOf("lowClosed")))
            assertEquals(false, resolver.navigatePropertyPath(interval, listOf("highClosed")))
        }

        @Test
        fun `unknown interval property returns null`() {
            assertEquals(null, resolver.navigatePropertyPath(Interval(CqlInteger(1), true, CqlInteger(10), false), listOf("point")))
        }

        @Test
        fun `non-fhir ClassInstance element resolves`() {
            val ci = ClassInstance(QName("urn:not-fhir", "Foo"), mutableMapOf("bar" to CqlString("x")))
            assertEquals(CqlString("x"), resolver.navigatePropertyPath(ci, listOf("bar")))
        }

        @Test
        fun `fhir ClassInstance normalizes then navigates`() {
            val value = resolver.navigatePropertyPath(fhirEncounterClassInstance("enc-1"), listOf("id"))
            assertNotNull(value)
            assertTrue(value is org.hl7.fhir.r4.model.IdType)
        }

        @Test
        fun `unknown property returns null`() {
            assertEquals(null, resolver.navigatePropertyPath(encounterWithPeriod(), listOf("nonExistentProperty")))
        }

        @Test
        fun `nullable root returns null`() {
            assertEquals(null, resolver.navigatePropertyPath(null, listOf("period")))
        }

        @Test
        fun `empty segments returns root`() {
            val encounter = encounterWithPeriod()
            assertEquals(encounter, resolver.navigatePropertyPath(encounter, emptyList()))
        }

        @Test
        fun `primitive root returns null`() {
            assertEquals(null, resolver.navigatePropertyPath(StringType("x"), listOf("length")))
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
        assertTrue(resolver.nextVarRef.get() > 1000)

        resolver.resetVarRefs()

        assertTrue(resolver.varRefs.isEmpty())
        assertTrue(resolver.varRefTypes.isEmpty())
        assertEquals(1000, resolver.nextVarRef.get())
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
        fun `query alias over a list source maps to its singular element type`() {
            val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/CoverageFixture2.cql")!!
            val compiler = compilationManager.compile(uri) ?: return
            val map = resolver.buildVariableTypeMap(compiler)
            // "AliasRefVal": {1, 2, 3} X where X > 1  =>  X is a query alias whose source is the
            // list {1,2,3}, so its AliasedQuerySource.resultType is List<System.Integer>. The debug
            // type map must de-list to the singular element type, mirroring the translator's in-body
            // AliasRef handling, so ad-hoc expressions type the alias as a single Integer.
            assertEquals("System.Integer", map["X"])
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

        @Test
        fun `FunctionDef list parameter is captured in type map`() {
            val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionParams.cql")!!
            val compiler = compilationManager.compile(uri) ?: return
            val map = resolver.buildVariableTypeMap(compiler)
            assertTrue(map.containsKey("conditions"), "FunctionDef operand 'conditions' should be in type map, got: $map")
            assertTrue(
                map["conditions"]!!.contains("Condition"),
                "Type for 'conditions' should reference Condition, got: ${map["conditions"]}",
            )
        }

        @Test
        fun `FunctionDef scalar parameter is captured in type map`() {
            val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionParams.cql")!!
            val compiler = compilationManager.compile(uri) ?: return
            val map = resolver.buildVariableTypeMap(compiler)
            assertTrue(map.containsKey("val"), "FunctionDef operand 'val' should be in type map, got: $map")
            assertTrue(
                map["val"]!!.contains("Integer"),
                "Type for 'val' should reference Integer, got: ${map["val"]}",
            )
        }

        @Test
        fun `FunctionDef with mixed params captures all operands`() {
            val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionParams.cql")!!
            val compiler = compilationManager.compile(uri) ?: return
            val map = resolver.buildVariableTypeMap(compiler)
            assertTrue(map.containsKey("a"), "FunctionDef operand 'a' should be in type map")
            assertTrue(map.containsKey("b"), "FunctionDef operand 'b' should be in type map")
            assertTrue(map["a"]!!.contains("Integer"), "Type for 'a' should reference Integer")
            assertTrue(map["b"]!!.contains("Condition"), "Type for 'b' should reference Condition")
        }
    }

    // -- fhirResourceTypeOf -------------------------------------------------

    @Nested
    inner class FhirResourceTypeOf {
        @Test
        fun `scalar resource returns bare type`() {
            val patient = Patient().apply { id = "test-123" }
            assertEquals("Patient", resolver.fhirResourceTypeOf(patient))
        }

        @Test
        fun `non-empty list of resources returns List type`() {
            val encounters =
                listOf(
                    Encounter().apply { id = "enc-1" },
                    Encounter().apply { id = "enc-2" },
                )
            assertEquals("List<Encounter>", resolver.fhirResourceTypeOf(encounters))
        }

        @Test
        fun `empty list returns null`() {
            assertNull(resolver.fhirResourceTypeOf(emptyList<Any>()))
        }

        @Test
        fun `list with non-resource elements returns null`() {
            assertNull(resolver.fhirResourceTypeOf(listOf("not-a-resource", 42)))
        }

        @Test
        fun `null value returns null`() {
            assertNull(resolver.fhirResourceTypeOf(null))
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
