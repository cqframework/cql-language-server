package org.opencds.cqf.cql.ls.server.command

import com.google.gson.Gson
import org.eclipse.lsp4j.ExecuteCommandParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.cql.ls.server.validation.testcase.TestCaseValidator
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidateTestCaseRequest
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidateTestCaseResponse
import java.nio.file.Files
import java.nio.file.Path

class ValidateTestCaseCommandContributionTest {
    private val contribution = ValidateTestCaseCommandContribution()

    @Test
    fun `getCommands returns validateTestCase command`() {
        assertEquals(
            setOf("org.opencds.cqf.cql.ls.validateTestCase"),
            contribution.getCommands(),
        )
    }

    @Test
    fun `validateTestCase on non-existent path returns error`() {
        val params =
            ExecuteCommandParams(
                "org.opencds.cqf.cql.ls.validateTestCase",
                listOf(Gson().toJsonTree(ValidateTestCaseRequest(path = "/non/existent/path"))),
            )
        val future = contribution.executeCommand(params)
        val response = future.get() as ValidateTestCaseResponse
        assertEquals(1, response.errorCount)
        assertEquals("ERR_PATH_NOT_FOUND", response.testCases.first().issues.first().code)
    }

    @Test
    fun `validateTestCase happy path with linked Patient and Encounter`(
        @TempDir tempDir: Path,
    ) {
        val testCaseDir = tempDir.resolve("patient-1")
        Files.createDirectories(testCaseDir)

        Files.writeString(
            testCaseDir.resolve("Patient-1.json"),
            """
            {
              "resourceType": "Patient",
              "id": "patient-1",
              "name": [{"family": "Smith", "given": ["John"]}]
            }
            """.trimIndent(),
        )

        Files.writeString(
            testCaseDir.resolve("Encounter-1.json"),
            """
            {
              "resourceType": "Encounter",
              "id": "enc-1",
              "status": "finished",
              "class": {"code": "AMB"},
              "subject": {"reference": "Patient/patient-1"}
            }
            """.trimIndent(),
        )

        val params =
            ExecuteCommandParams(
                "org.opencds.cqf.cql.ls.validateTestCase",
                listOf(Gson().toJsonTree(ValidateTestCaseRequest(path = testCaseDir.toString()))),
            )
        val response = contribution.executeCommand(params).get() as ValidateTestCaseResponse

        assertEquals(1, response.testCaseCount)
        assertEquals(2, response.resourceCount)
        assertEquals(0, response.errorCount, "Expected 0 errors, got: ${response.testCases.first().issues}")
        assertEquals(0, response.warningCount, "Expected 0 warnings, got: ${response.testCases.first().issues}")
    }

    @Test
    fun `validateTestCase detects duplicate IDs, patient mismatch, and dangling references`(
        @TempDir tempDir: Path,
    ) {
        val testCaseDir = tempDir.resolve("patient-dup")
        Files.createDirectories(testCaseDir)

        Files.writeString(
            testCaseDir.resolve("Patient-1.json"),
            """
            {
              "resourceType": "Patient",
              "id": "pat-1"
            }
            """.trimIndent(),
        )

        // Duplicate Encounter id
        Files.writeString(
            testCaseDir.resolve("Encounter-1.json"),
            """
            {
              "resourceType": "Encounter",
              "id": "enc-1",
              "status": "finished",
              "class": {"code": "AMB"},
              "subject": {"reference": "Patient/pat-1"}
            }
            """.trimIndent(),
        )

        Files.writeString(
            testCaseDir.resolve("Encounter-1-dup.json"),
            """
            {
              "resourceType": "Encounter",
              "id": "enc-1",
              "status": "finished",
              "class": {"code": "AMB"},
              "subject": {"reference": "Patient/wrong-pat"},
              "serviceProvider": {"reference": "Organization/missing-org"}
            }
            """.trimIndent(),
        )

        // Condition with missing clinicalStatus
        Files.writeString(
            testCaseDir.resolve("Condition-1.json"),
            """
            {
              "resourceType": "Condition",
              "id": "cond-1",
              "subject": {"reference": "Patient/pat-1"}
            }
            """.trimIndent(),
        )

        val params =
            ExecuteCommandParams(
                "org.opencds.cqf.cql.ls.validateTestCase",
                listOf(Gson().toJsonTree(ValidateTestCaseRequest(path = testCaseDir.toString()))),
            )
        val response = contribution.executeCommand(params).get() as ValidateTestCaseResponse

        val issues = response.testCases.first().issues
        val codes = issues.map { it.code }.toSet()

        assertTrue(codes.contains("ERR_DUPLICATE_ID"), "Expected ERR_DUPLICATE_ID in $codes")
        assertTrue(codes.contains("ERR_PATIENT_MISMATCH"), "Expected ERR_PATIENT_MISMATCH in $codes")
        assertTrue(codes.contains("ERR_DANGLING_REFERENCE"), "Expected ERR_DANGLING_REFERENCE in $codes")
        assertTrue(codes.contains("WARN_MISSING_REQUIRED_FIELD"), "Expected WARN_MISSING_REQUIRED_FIELD in $codes")
    }

    @Test
    fun `validateTestCase resolves shared directory references`(
        @TempDir tempDir: Path,
    ) {
        val suiteDir = tempDir.resolve("MyMeasure")
        val sharedDir = suiteDir.resolve("shared")
        val caseDir = suiteDir.resolve("case-1")
        Files.createDirectories(sharedDir)
        Files.createDirectories(caseDir)

        Files.writeString(
            sharedDir.resolve("Practitioner-doc1.json"),
            """
            {
              "resourceType": "Practitioner",
              "id": "doc-1"
            }
            """.trimIndent(),
        )

        Files.writeString(
            caseDir.resolve("Patient-1.json"),
            """
            {
              "resourceType": "Patient",
              "id": "pat-1"
            }
            """.trimIndent(),
        )

        Files.writeString(
            caseDir.resolve("Encounter-1.json"),
            """
            {
              "resourceType": "Encounter",
              "id": "enc-1",
              "status": "finished",
              "class": {"code": "AMB"},
              "subject": {"reference": "Patient/pat-1"},
              "participant": [
                {"individual": {"reference": "Practitioner/doc-1"}}
              ]
            }
            """.trimIndent(),
        )

        val params =
            ExecuteCommandParams(
                "org.opencds.cqf.cql.ls.validateTestCase",
                listOf(Gson().toJsonTree(ValidateTestCaseRequest(path = caseDir.toString()))),
            )
        val response = contribution.executeCommand(params).get() as ValidateTestCaseResponse
        assertEquals(0, response.errorCount, "Shared Practitioner should resolve cleanly without errors: ${response.testCases.first().issues}")
    }

    @Test
    fun `path normalization handles posix, file uri and windows format`() {
        val p1 = TestCaseValidator.normalizePath("/tmp/test/path")
        assertNotNull(p1)

        val p2 = TestCaseValidator.normalizePath("file:///tmp/test/path")
        assertNotNull(p2)

        val p3 = TestCaseValidator.normalizePath("C:/test/path")
        assertNotNull(p3)
    }
}
