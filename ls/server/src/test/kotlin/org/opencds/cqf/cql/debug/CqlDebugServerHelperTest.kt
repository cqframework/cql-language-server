package org.opencds.cqf.cql.debug

import org.hl7.elm.r1.Interval
import org.hl7.elm.r1.IntervalTypeSpecifier
import org.hl7.elm.r1.ListTypeSpecifier
import org.hl7.elm.r1.Literal
import org.hl7.elm.r1.NamedTypeSpecifier
import org.hl7.elm.r1.Null
import org.hl7.elm.r1.ParameterDef
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Period
import org.hl7.fhir.r4.model.StringType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import java.nio.file.Paths
import javax.xml.namespace.QName

class CqlDebugServerHelperTest {
    private fun makeServer(): CqlDebugServer {
        val cs = Mockito.mock(ContentService::class.java)
        val cm = Mockito.mock(CqlCompilationManager::class.java)
        val ig = Mockito.mock(IgContextManager::class.java)
        val lrm = Mockito.mock(LibraryResolutionManager::class.java)
        return object : CqlDebugServer(cm, cs, ig, lrm) {
            override fun executeLaunch(args: DebugLaunchArgs) {}

            override fun executeLaunchStreaming(args: DebugLaunchArgs) {}
        }
    }

    // -- serializeTypeSpecifier ---------------------------------------------

    @Test
    fun `serializeTypeSpecifier with NamedTypeSpecifier returns localPart`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "serializeTypeSpecifier",
                Any::class.java,
            )
        method.isAccessible = true
        val spec = NamedTypeSpecifier().also { it.name = QName("http://hl7.org/fhir", "System.Integer") }
        val result = method.invoke(server, spec) as String
        assertEquals("System.Integer", result)
    }

    @Test
    fun `serializeTypeSpecifier with IntervalTypeSpecifier returns Interval format`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "serializeTypeSpecifier",
                Any::class.java,
            )
        method.isAccessible = true
        val pointType = NamedTypeSpecifier().also { it.name = QName("http://hl7.org/fhir", "System.DateTime") }
        val spec = IntervalTypeSpecifier().also { it.pointType = pointType }
        val result = method.invoke(server, spec) as String
        assertEquals("Interval<System.DateTime>", result)
    }

    @Test
    fun `serializeTypeSpecifier with ListTypeSpecifier returns List format`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "serializeTypeSpecifier",
                Any::class.java,
            )
        method.isAccessible = true
        val elementType = NamedTypeSpecifier().also { it.name = QName("http://hl7.org/fhir", "System.String") }
        val spec = ListTypeSpecifier().also { it.elementType = elementType }
        val result = method.invoke(server, spec) as String
        assertEquals("List<System.String>", result)
    }

    @Test
    fun `serializeTypeSpecifier with unknown type returns class simple name`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "serializeTypeSpecifier",
                Any::class.java,
            )
        method.isAccessible = true
        val result = method.invoke(server, "just a string") as String
        assertEquals("String", result)
    }

    @Test
    fun `serializeTypeSpecifier with null returns Unknown`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "serializeTypeSpecifier",
                Any::class.java,
            )
        method.isAccessible = true
        val result = method.invoke(server, null) as String
        assertEquals("Unknown", result)
    }

    @Test
    fun `serializeTypeSpecifier NamedTypeSpecifier null name returns Unknown`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "serializeTypeSpecifier",
                Any::class.java,
            )
        method.isAccessible = true
        val spec = NamedTypeSpecifier()
        val result = method.invoke(server, spec) as String
        assertEquals("Unknown", result)
    }

    // -- extractDefaultValue ------------------------------------------------

    @Test
    fun `extractDefaultValue with Literal returns value`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "extractDefaultValue",
                ParameterDef::class.java,
            )
        method.isAccessible = true
        val paramDef =
            ParameterDef().also {
                it.default = Literal().also { l -> l.value = "42" }
            }
        val result = method.invoke(server, paramDef) as String
        assertEquals("42", result)
    }

    @Test
    fun `extractDefaultValue with Literal null value returns null string`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "extractDefaultValue",
                ParameterDef::class.java,
            )
        method.isAccessible = true
        val paramDef =
            ParameterDef().also {
                it.default = Literal().also { l -> l.value = null }
            }
        val result = method.invoke(server, paramDef) as String
        assertEquals("null", result)
    }

    @Test
    fun `extractDefaultValue with Interval returns formatted interval`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "extractDefaultValue",
                ParameterDef::class.java,
            )
        method.isAccessible = true
        val lowLiteral = Literal().also { it.value = "2026-01-01" }
        val highLiteral = Literal().also { it.value = "2027-01-01" }
        val interval =
            Interval().also {
                it.low = lowLiteral
                it.high = highLiteral
            }
        val paramDef = ParameterDef().also { it.default = interval }
        val result = method.invoke(server, paramDef) as String
        assertEquals("Interval[2026-01-01, 2027-01-01]", result)
    }

    @Test
    fun `extractDefaultValue null default returns null`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "extractDefaultValue",
                ParameterDef::class.java,
            )
        method.isAccessible = true
        val paramDef = ParameterDef()
        val result = method.invoke(server, paramDef)
        assertEquals(null, result)
    }

    @Test
    fun `extractDefaultValue with non-Literal non-Interval returns class name`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "extractDefaultValue",
                ParameterDef::class.java,
            )
        method.isAccessible = true
        val paramDef =
            ParameterDef().also {
                it.default = Null()
            }
        val result = method.invoke(server, paramDef) as String
        assertEquals("(Null)", result)
    }

    // -- isExpandable -------------------------------------------------------

    @Test
    fun `isExpandable null returns false`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "isExpandable",
                Any::class.java,
            )
        method.isAccessible = true
        assertFalse(method.invoke(server, null) as Boolean)
    }

    @Test
    fun `isExpandable IPrimitiveType returns false`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "isExpandable",
                Any::class.java,
            )
        method.isAccessible = true
        assertFalse(method.invoke(server, StringType("test")) as Boolean)
    }

    @Test
    fun `isExpandable IBase returns true`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "isExpandable",
                Any::class.java,
            )
        method.isAccessible = true
        val encounter = Encounter()
        assertTrue(method.invoke(server, encounter) as Boolean)
    }

    @Test
    fun `isExpandable non-empty list returns true`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "isExpandable",
                Any::class.java,
            )
        method.isAccessible = true
        assertTrue(method.invoke(server, listOf(Encounter())) as Boolean)
    }

    @Test
    fun `isExpandable empty list returns false`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "isExpandable",
                Any::class.java,
            )
        method.isAccessible = true
        assertFalse(method.invoke(server, emptyList<Any>()) as Boolean)
    }

    @Test
    fun `isExpandable plain string returns false`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "isExpandable",
                Any::class.java,
            )
        method.isAccessible = true
        assertFalse(method.invoke(server, "hello") as Boolean)
    }

    // -- formatPeriodAsInterval ---------------------------------------------

    @Test
    fun `formatPeriodAsInterval with start and end`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "formatPeriodAsInterval",
                Period::class.java,
            )
        method.isAccessible = true
        val period =
            Period().also {
                it.start = java.util.Date(1700000000000L)
                it.end = java.util.Date(1800000000000L)
            }
        val result = method.invoke(server, period) as String
        assertTrue(result.startsWith("["))
        assertTrue(result.endsWith(")"))
    }

    @Test
    fun `formatPeriodAsInterval null start end`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "formatPeriodAsInterval",
                Period::class.java,
            )
        method.isAccessible = true
        val period = Period()
        val result = method.invoke(server, period) as String
        assertEquals("[null, null)", result)
    }

    // -- registerIfExpandable ------------------------------------------------

    @Test
    fun `registerIfExpandable with null returns 0`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "registerIfExpandable",
                Any::class.java,
                String::class.java,
            )
        method.isAccessible = true
        val result = method.invoke(server, null, null) as Int
        assertEquals(0, result)
    }

    @Test
    fun `registerIfExpandable with IBase returns positive ref`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "registerIfExpandable",
                Any::class.java,
                String::class.java,
            )
        method.isAccessible = true
        val encounter = Encounter()
        val result = method.invoke(server, encounter, "Encounter") as Int
        assertTrue(result > 0)
    }

    @Test
    fun `registerIfExpandable with IBase and no typeName still stores ref`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "registerIfExpandable",
                Any::class.java,
                String::class.java,
            )
        method.isAccessible = true
        val result = method.invoke(server, Encounter(), null) as Int
        assertTrue(result > 0)
    }

    @Test
    fun `registerIfExpandable uses successive ref values`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "registerIfExpandable",
                Any::class.java,
                String::class.java,
            )
        method.isAccessible = true
        val r1 = method.invoke(server, Encounter(), null) as Int
        val r2 = method.invoke(server, Encounter(), null) as Int
        assertTrue(r2 > r1)
    }

    // -- collectTransitiveIncludes -------------------------------------------

    @Test
    fun `collectTransitiveIncludes null compiler returns setOf primaryId`() {
        val server = makeServer()
        val result = server.collectTransitiveIncludes("primary-lib", null)
        assertEquals(setOf("primary-lib"), result)
    }

    // -- resolveSource -------------------------------------------------------

    @Test
    fun `resolveSource with nonExistentLib returns sourceReference 0`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "resolveSource",
                String::class.java,
            )
        method.isAccessible = true
        val result = method.invoke(server, "nonExistentLib") as org.eclipse.lsp4j.debug.Source
        assertEquals(0, result.sourceReference)
    }

    @Test
    fun `resolveSource empty libraryId falls through to sourceReference`() {
        val server = makeServer()
        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "resolveSource",
                String::class.java,
            )
        method.isAccessible = true
        val result = method.invoke(server, "") as org.eclipse.lsp4j.debug.Source
        assertEquals(0, result.sourceReference)
    }

    // -- resolveSource via librarySourceMap ----------------------------------

    @Test
    fun `resolveSource uses librarySourceMap if present`() {
        val server = makeServer()
        val libSourceMapField = CqlDebugServer::class.java.getDeclaredField("librarySourceMap")
        libSourceMapField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = libSourceMapField.get(server) as MutableMap<String, java.net.URI>
        map["test-lib"] = java.net.URI.create("file:///test/path.cql")

        val method =
            CqlDebugServer::class.java.getDeclaredMethod(
                "resolveSource",
                String::class.java,
            )
        method.isAccessible = true
        val result = method.invoke(server, "test-lib") as org.eclipse.lsp4j.debug.Source
        assertEquals(Paths.get(java.net.URI.create("file:///test/path.cql")).toString(), result.path)
    }
}
