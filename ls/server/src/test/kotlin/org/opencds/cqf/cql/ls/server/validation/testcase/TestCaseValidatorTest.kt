package org.opencds.cqf.cql.ls.server.validation.testcase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class TestCaseValidatorTest {
    private val validator = TestCaseValidator()

    private fun validate(path: Path): ValidateTestCaseResponse = validator.validate(ValidateTestCaseRequest(path = path.toString()))

    private fun issues(response: ValidateTestCaseResponse): List<ValidationIssue> = response.testCases.flatMap { it.issues }

    private fun writeCase(
        tempDir: Path,
        vararg files: Pair<String, String>,
    ): Path {
        val caseDir = tempDir.resolve("case-1")
        Files.createDirectories(caseDir)
        files.forEach { (name, content) -> Files.writeString(caseDir.resolve(name), content.trimIndent()) }
        return caseDir
    }

    private val patient =
        """
        {
          "resourceType": "Patient",
          "id": "pat-1"
        }
        """

    @Test
    fun `contained reference resolves and missing contained id is flagged`(
        @TempDir tempDir: Path,
    ) {
        val caseDir =
            writeCase(
                tempDir,
                "Patient.json" to patient,
                "MedicationRequest.json" to
                    """
                    {
                      "resourceType": "MedicationRequest",
                      "id": "mr-1",
                      "status": "active",
                      "intent": "order",
                      "contained": [{"resourceType": "Medication", "id": "med-1"}],
                      "medicationReference": {"reference": "#med-1"},
                      "subject": {"reference": "Patient/pat-1"},
                      "reasonReference": [{"reference": "#missing"}]
                    }
                    """,
            )

        val dangling = issues(validate(caseDir)).filter { it.code == "ERR_DANGLING_REFERENCE" }
        assertEquals(1, dangling.size, "Expected only #missing to dangle: $dangling")
        assertTrue(dangling.first().message.contains("#missing"))
    }

    @Test
    fun `versioned reference resolves`(
        @TempDir tempDir: Path,
    ) {
        val caseDir =
            writeCase(
                tempDir,
                "Patient.json" to
                    """
                    {
                      "resourceType": "Patient",
                      "id": "1"
                    }
                    """,
                "Encounter.json" to
                    """
                    {
                      "resourceType": "Encounter",
                      "id": "enc-1",
                      "status": "finished",
                      "class": {"code": "AMB"},
                      "subject": {"reference": "Patient/1/_history/2"}
                    }
                    """,
            )

        val response = validate(caseDir)
        assertEquals(0, response.errorCount, "Unexpected issues: ${issues(response)}")
    }

    @Test
    fun `transaction bundle urn uuid subject matches patient fullUrl`(
        @TempDir tempDir: Path,
    ) {
        val caseDir =
            writeCase(
                tempDir,
                "bundle.json" to
                    """
                    {
                      "resourceType": "Bundle",
                      "type": "transaction",
                      "entry": [
                        {
                          "fullUrl": "urn:uuid:aaaa-1111",
                          "resource": {
                            "resourceType": "Patient",
                            "id": "pat-y"
                          }
                        },
                        {
                          "fullUrl": "urn:uuid:bbbb-2222",
                          "resource": {
                            "resourceType": "Encounter",
                            "id": "bbbb-2222",
                            "status": "finished",
                            "class": {"code": "AMB"},
                            "subject": {"reference": "urn:uuid:aaaa-1111"}
                          }
                        }
                      ]
                    }
                    """,
            )

        val all = issues(validate(caseDir))
        assertTrue(all.none { it.code == "ERR_PATIENT_MISMATCH" }, "Unexpected mismatch: $all")
        assertTrue(all.none { it.code == "ERR_DANGLING_REFERENCE" }, "Unexpected dangling: $all")
    }

    @Test
    fun `patient with only a fullUrl still enables linkage check`(
        @TempDir tempDir: Path,
    ) {
        val caseDir =
            writeCase(
                tempDir,
                "bundle.json" to
                    """
                    {
                      "resourceType": "Bundle",
                      "type": "transaction",
                      "entry": [
                        {"fullUrl": "urn:uuid:aaaa-1111", "resource": {"resourceType": "Patient"}},
                        {
                          "fullUrl": "urn:uuid:bbbb-2222",
                          "resource": {
                            "resourceType": "Encounter",
                            "id": "bbbb-2222",
                            "status": "finished",
                            "class": {"code": "AMB"},
                            "subject": {"reference": "urn:uuid:other"}
                          }
                        }
                      ]
                    }
                    """,
            )

        val response = validate(caseDir)
        assertEquals("aaaa-1111", response.testCases.first().patientId)
        assertEquals(1, issues(response).count { it.code == "ERR_PATIENT_MISMATCH" }, "Issues: ${issues(response)}")
    }

    @Test
    fun `measure report with wrong subject gives exactly one error`(
        @TempDir tempDir: Path,
    ) {
        val caseDir =
            writeCase(
                tempDir,
                "Patient.json" to patient,
                "MeasureReport.json" to
                    """
                    {
                      "resourceType": "MeasureReport",
                      "id": "mr-1",
                      "subject": {"reference": "Patient/wrong-pat"}
                    }
                    """,
            )

        val subjectErrors =
            issues(validate(caseDir)).filter { it.code == "ERR_MEASURE_REPORT_SUBJECT" || it.code == "ERR_PATIENT_MISMATCH" }
        assertEquals(1, subjectErrors.size, "Expected exactly one subject error: $subjectErrors")
        assertEquals("ERR_MEASURE_REPORT_SUBJECT", subjectErrors.first().code)
    }

    @Test
    fun `case folder with only malformed JSON reports ERR_INVALID_JSON with line number`(
        @TempDir tempDir: Path,
    ) {
        val caseDir =
            writeCase(
                tempDir,
                "Patient.json" to
                    """
                    {
                      "resourceType": "Patient",
                      "id": "pat-1",
                      "name": [
                    }
                    """,
            )

        val response = validate(caseDir)
        assertEquals(1, response.testCaseCount)
        val invalid = issues(response).filter { it.code == "ERR_INVALID_JSON" }
        assertEquals(1, invalid.size, "Issues: ${issues(response)}")
        assertEquals(ValidationSeverity.ERROR, invalid.first().severity)
        assertNotNull(invalid.first().line)
        assertTrue(invalid.first().line!! > 1, "Expected a line from the parser message, got ${invalid.first().line}")
    }

    @Test
    fun `non-string resourceType reports ERR_INVALID_JSON and object without resourceType is ignored`(
        @TempDir tempDir: Path,
    ) {
        val caseDir =
            writeCase(
                tempDir,
                "Patient.json" to patient,
                "config.json" to """{"setting": true}""",
                "Broken.json" to """{"resourceType": {"oops": 1}}""",
            )

        val codes = issues(validate(caseDir)).map { it.code }
        assertEquals(1, codes.count { it == "ERR_INVALID_JSON" }, "Codes: $codes")
    }

    @Test
    fun `ids and references containing regex metacharacters do not throw`(
        @TempDir tempDir: Path,
    ) {
        val caseDir =
            writeCase(
                tempDir,
                "Patient.json" to
                    """
                    {
                      "resourceType": "Patient",
                      "id": "pat(1"
                    }
                    """,
                "Encounter-a.json" to
                    """
                    {
                      "resourceType": "Encounter",
                      "id": "enc(1",
                      "status": "finished",
                      "class": {"code": "AMB"},
                      "subject": {"reference": "Patient/other(2"}
                    }
                    """,
                "Encounter-b.json" to
                    """
                    {
                      "resourceType": "Encounter",
                      "id": "enc(1",
                      "status": "finished",
                      "class": {"code": "AMB"},
                      "subject": {"reference": "Patient/pat(1"}
                    }
                    """,
            )

        val codes = issues(validate(caseDir)).map { it.code }
        assertTrue(codes.contains("ERR_DUPLICATE_ID"), "Codes: $codes")
        assertTrue(codes.contains("ERR_PATIENT_MISMATCH"), "Codes: $codes")
    }

    @Test
    fun `missing required fields in two bundle conditions get different lines`(
        @TempDir tempDir: Path,
    ) {
        val caseDir =
            writeCase(
                tempDir,
                "bundle.json" to
                    """
                    {
                      "resourceType": "Bundle",
                      "type": "collection",
                      "entry": [
                        {
                          "resource": {
                            "resourceType": "Patient",
                            "id": "pat-1"
                          }
                        },
                        {
                          "resource": {
                            "resourceType": "Condition",
                            "id": "cond-1",
                            "subject": {"reference": "Patient/pat-1"}
                          }
                        },
                        {
                          "resource": {
                            "resourceType": "Condition",
                            "id": "cond-2",
                            "subject": {"reference": "Patient/pat-1"}
                          }
                        }
                      ]
                    }
                    """,
            )

        val missing = issues(validate(caseDir)).filter { it.code == "WARN_MISSING_REQUIRED_FIELD" }
        assertEquals(2, missing.size, "Issues: $missing")
        val byId = missing.associateBy { it.resourceId }
        assertEquals(14, byId["cond-1"]?.line)
        assertEquals(21, byId["cond-2"]?.line)
        assertNotEquals(byId["cond-1"]?.line, byId["cond-2"]?.line)
    }

    @Test
    fun `fullUrl mismatch is reported as WARN_FULLURL_MISMATCH`(
        @TempDir tempDir: Path,
    ) {
        val caseDir =
            writeCase(
                tempDir,
                "bundle.json" to
                    """
                    {
                      "resourceType": "Bundle",
                      "type": "collection",
                      "entry": [
                        {
                          "fullUrl": "http://example.org/fhir/Patient/something-else",
                          "resource": {
                            "resourceType": "Patient",
                            "id": "pat-1"
                          }
                        }
                      ]
                    }
                    """,
            )

        val mismatch = issues(validate(caseDir)).filter { it.code.endsWith("FULLURL_MISMATCH") }
        assertEquals(1, mismatch.size, "Issues: $mismatch")
        assertEquals("WARN_FULLURL_MISMATCH", mismatch.first().code)
        assertEquals(ValidationSeverity.WARNING, mismatch.first().severity)
        assertEquals(6, mismatch.first().line)
    }

    /**
     * normalizePath expectations per platform:
     *
     * | Input                | macOS/Linux        | Windows       | Branch                 |
     * | -------------------- | ------------------ | ------------- | ---------------------- |
     * | `/a/b`               | `/a/b`             | `\a\b`        | plain path             |
     * | `C:\a\b`             | `C:\a\b` (1 name)  | `C:\a\b`      | plain path             |
     * | `C:/a/b`             | `C:/a/b` (relative)| `C:\a\b`      | plain path             |
     * | `file:///a/b`        | `/a/b`             | `\a\b`        | URI                    |
     * | `file:///C:/a/b`     | `/C:/a/b`          | `C:\a\b`      | URI                    |
     * | `file:///C:\a\b`     | `C:\a\b`           | `C:\a\b`      | fallback, `/C:` strip  |
     * | `file:C:\a\b`        | `C:\a\b`           | `C:\a\b`      | fallback               |
     * | `file:///a/b\c`      | `/a/b\c`           | `\a\b\c`      | fallback, no drive     |
     */
    @Test
    fun `normalizePath handles posix, windows and file uri forms`() {
        val isWindows = File.separatorChar == '\\'
        val cases =
            listOf(
                "/a/b" to "/a/b",
                "C:\\a\\b" to "C:\\a\\b",
                "C:/a/b" to "C:/a/b",
                "file:///a/b" to "/a/b",
                "file:///C:/a/b" to if (isWindows) "C:/a/b" else "/C:/a/b",
                "file:///C:\\a\\b" to "C:\\a\\b",
                "file:C:\\a\\b" to "C:\\a\\b",
                "file:///a/b\\c" to "/a/b\\c",
            )
        for ((input, expected) in cases) {
            assertEquals(Paths.get(expected), TestCaseValidator.normalizePath(input), "normalizePath($input)")
        }
    }
}
