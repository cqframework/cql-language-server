package org.opencds.cqf.cql.ls.server.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExecuteCqlRequestTest {
    private fun makeLibraryRequest(name: String = "TestLib") =
        LibraryRequest(
            libraryName = name,
            libraryUri = "file:///test/$name.cql",
            libraryVersion = "1.0.0",
            terminologyUri = null,
            model = null,
            context = ContextRequest("Patient", "patient-1"),
            parameters = listOf(ParameterRequest("MP", "Interval<DateTime>", "Interval[@2026-01-01, @2027-01-01)")),
        )

    @Test
    fun `ExecuteCqlRequest equality holds for identical instances`() {
        val lib = makeLibraryRequest()
        val r1 = ExecuteCqlRequest("R4", "/root", null, listOf(lib))
        val r2 = ExecuteCqlRequest("R4", "/root", null, listOf(lib))
        assertEquals(r1, r2)
    }

    @Test
    fun `ExecuteCqlRequest copy with changed field is not equal to original`() {
        val lib = makeLibraryRequest()
        val r1 = ExecuteCqlRequest("R4", "/root", null, listOf(lib))
        val r2 = r1.copy(fhirVersion = "STU3")
        assertNotEquals(r1, r2)
        assertEquals("STU3", r2.fhirVersion)
        assertEquals("R4", r1.fhirVersion)
    }

    @Test
    fun `ExecuteCqlRequest toString contains key field values`() {
        val lib = makeLibraryRequest()
        val request = ExecuteCqlRequest("R4", "/myroot", "/options.json", listOf(lib))
        val str = request.toString()
        assertTrue(str.contains("R4"), "toString should contain fhirVersion: $str")
        assertTrue(str.contains("/myroot"), "toString should contain rootDir: $str")
    }

    @Test
    fun `LibraryRequest equality holds for identical instances`() {
        val l1 = makeLibraryRequest("MyLib")
        val l2 = makeLibraryRequest("MyLib")
        assertEquals(l1, l2)
    }

    @Test
    fun `LibraryRequest copy changes only specified field`() {
        val original = makeLibraryRequest("Lib")
        val modified = original.copy(libraryVersion = "2.0.0")
        assertEquals("2.0.0", modified.libraryVersion)
        assertEquals("Lib", modified.libraryName)
    }

    @Test
    fun `ParameterRequest equality holds`() {
        val p1 = ParameterRequest("MP", "Interval<DateTime>", "Interval[@2026, @2027)")
        val p2 = ParameterRequest("MP", "Interval<DateTime>", "Interval[@2026, @2027)")
        assertEquals(p1, p2)
    }

    @Test
    fun `ModelRequest equality holds`() {
        val m1 = ModelRequest("FHIR", "file:///data.json")
        val m2 = ModelRequest("FHIR", "file:///data.json")
        assertEquals(m1, m2)
    }

    @Test
    fun `ContextRequest equality holds`() {
        val c1 = ContextRequest("Patient", "pat-1")
        val c2 = ContextRequest("Patient", "pat-1")
        assertEquals(c1, c2)
    }

    @Test
    fun `ExpressionResult equality holds`() {
        val e1 = ExpressionResult("Initial Pop", "true")
        val e2 = ExpressionResult("Initial Pop", "true")
        assertEquals(e1, e2)
    }

    @Test
    fun `VersionInfo equality holds`() {
        val v1 = VersionInfo("4.5.0", "3.0.0", "4.0.0", "4.8.0")
        val v2 = VersionInfo("4.5.0", "3.0.0", "4.0.0", "4.8.0")
        assertEquals(v1, v2)
    }

    @Test
    fun `ExecuteCqlResponse equality holds`() {
        val r1 =
            ExecuteCqlResponse(
                results = listOf(LibraryResult("TestLib", listOf(ExpressionResult("A", "1")))),
                logs = listOf("log line"),
                versions = null,
            )
        val r2 = r1.copy()
        assertEquals(r1, r2)
    }

    @Test
    fun `DetailedEvaluationResult equality holds`() {
        val d1 =
            DetailedEvaluationResult(
                response = ExecuteCqlResponse(emptyList(), emptyList()),
                subExpressions = emptyList(),
                defineOrder = listOf("A", "B"),
            )
        val d2 = d1.copy()
        assertEquals(d1, d2)
    }
}
