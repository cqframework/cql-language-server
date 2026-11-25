package org.opencds.cqf.cql.ls.server.validation.profile

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.server.validation.testcase.TestCaseValidator
import java.nio.file.Paths

class CaseResourceIndexTest {
    private fun raw(
        type: String,
        id: String?,
        fullUrl: String? = null,
        marker: String = "case",
    ) = TestCaseValidator.RawResource(
        resourceType = type,
        id = id,
        fullUrl = fullUrl,
        filePath = Paths.get("$type.json"),
        jsonObject = JsonParser.parseString("""{"resourceType":"$type"${id?.let { ""","id":"$it"""" } ?: ""},"marker":"$marker"}""").asJsonObject,
        lines = emptyList(),
    )

    @Test
    fun `indexes Type slash id, urn uuid of the id, and fullUrl`() {
        val index =
            CaseResourceIndex.of(
                listOf(
                    raw("Patient", "p1"),
                    raw("Encounter", "e1", fullUrl = "urn:uuid:5b8c0000-0000-4000-8000-000000000001"),
                    raw("Condition", "c1", fullUrl = "http://example.org/fhir/Condition/c1"),
                ),
            )
        assertEquals(
            setOf(
                "Patient/p1",
                "urn:uuid:p1",
                "Encounter/e1",
                "urn:uuid:e1",
                "urn:uuid:5b8c0000-0000-4000-8000-000000000001",
                "Condition/c1",
                "urn:uuid:c1",
                "http://example.org/fhir/Condition/c1",
            ),
            index.keys,
        )
        assertNotNull(index.lookup("urn:uuid:5b8c0000-0000-4000-8000-000000000001"))
        assertNotNull(index.lookup("http://example.org/fhir/Condition/c1"))
    }

    @Test
    fun `history versions resolve to the unversioned resource`() {
        val index = CaseResourceIndex.of(listOf(raw("Patient", "p1")))
        assertNotNull(index.lookup("Patient/p1/_history/3"))
        assertNull(index.lookup("Patient/p2"))
        assertNull(index.lookup("Practitioner/p1"))
    }

    @Test
    fun `resource without id is only reachable by fullUrl`() {
        val index = CaseResourceIndex.of(listOf(raw("Observation", null, fullUrl = "urn:uuid:abc")))
        assertEquals(setOf("urn:uuid:abc"), index.keys)
    }

    @Test
    fun `case resources win over shared ones and lookupJson serializes`() {
        val index = CaseResourceIndex.of(listOf(raw("Patient", "p1", marker = "case"), raw("Patient", "p1", marker = "shared")))
        val json = index.lookupJson("Patient/p1")
        assertNotNull(json)
        assertTrue(json!!.contains("\"marker\":\"case\""), json)
    }
}
