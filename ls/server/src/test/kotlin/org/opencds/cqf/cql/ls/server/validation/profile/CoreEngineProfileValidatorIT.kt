package org.opencds.cqf.cql.ls.server.validation.profile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.cql.ls.server.validation.testcase.IssueSource
import org.opencds.cqf.cql.ls.server.validation.testcase.ProfileMode
import org.opencds.cqf.cql.ls.server.validation.testcase.ProfileStatus
import org.opencds.cqf.cql.ls.server.validation.testcase.TestCaseValidator
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidateTestCaseOptions
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidateTestCaseRequest
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds the real HL7 core engine (R4 core only, from the local FHIR package cache) and validates a
 * temp case. Slow; run with `-Dfhir.it=true -Dtest='*ProfileValidator*IT*'`.
 */
@EnabledIfSystemProperty(named = "fhir.it", matches = "true")
class CoreEngineProfileValidatorIT {
    @Test
    fun `dangling reference and wrong target type are reported by the core engine`(
        @TempDir tempDir: Path,
    ) {
        Files.writeString(tempDir.resolve("ig.ini"), "[IG]\nig = input/ig.xml\n")
        val caseDir = tempDir.resolve("input/tests/measure/M/case-1")
        Files.createDirectories(caseDir)
        Files.writeString(caseDir.resolve("Patient.json"), """{"resourceType": "Patient", "id": "pat-1", "gender": "female"}""")
        Files.writeString(caseDir.resolve("Practitioner.json"), """{"resourceType": "Practitioner", "id": "prac-1"}""")
        Files.writeString(
            caseDir.resolve("Encounter.json"),
            """
            {
              "resourceType": "Encounter",
              "id": "enc-1",
              "status": "finished",
              "class": {"system": "http://terminology.hl7.org/CodeSystem/v3-ActCode", "code": "IMP"},
              "subject": {"reference": "Practitioner/prac-1"},
              "reasonReference": [{"reference": "Condition/does-not-exist"}]
            }
            """.trimIndent(),
        )

        val engine = CoreEngineProfileValidator.build(ProfileEngineSpec(tempDir, emptyList()))
        val provider =
            object : ProfileValidatorProvider {
                override fun acquire(
                    path: Path,
                    packages: List<String>,
                    mode: EngineMode,
                ) = ProfileValidatorLookup(ProfileEngineState.READY, engine)
            }

        for (mode in listOf(ProfileMode.QUICK, ProfileMode.FULL)) {
            val response = TestCaseValidator(provider).validate(ValidateTestCaseRequest(caseDir.toString(), ValidateTestCaseOptions(profile = mode)))
            assertEquals(ProfileStatus.RAN, response.profileStatus)
            val core = response.testCases.single().issues.filter { it.source == IssueSource.CORE }
            val codes = core.map { it.code }
            assertTrue("Reference_REF_CantResolve" in codes, "$mode: $core")
            assertTrue("Reference_REF_BadTargetType" in codes, "$mode: $core")
            val cantResolve = core.first { it.code == "Reference_REF_CantResolve" }
            assertTrue(cantResolve.filePath!!.endsWith("Encounter.json"))
            assertTrue((cantResolve.line ?: 0) > 0, "core issues carry a line: $cantResolve")

            val unchecked =
                TestCaseValidator(provider).validate(ValidateTestCaseRequest(caseDir.toString(), ValidateTestCaseOptions(profile = mode, checkReferences = false)))
            val referenceIssues = unchecked.testCases.single().issues.filter { it.source == IssueSource.CORE && it.code.startsWith("Reference_REF_") }
            assertEquals(emptyList<Any>(), referenceIssues, "$mode with checkReferences=false")
        }
    }
}
