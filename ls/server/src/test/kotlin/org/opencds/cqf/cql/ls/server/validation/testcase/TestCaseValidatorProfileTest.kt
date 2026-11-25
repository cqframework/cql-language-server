package org.opencds.cqf.cql.ls.server.validation.testcase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.cql.ls.server.validation.profile.CaseResourceIndex
import org.opencds.cqf.cql.ls.server.validation.profile.EngineMode
import org.opencds.cqf.cql.ls.server.validation.profile.ProfileEngineState
import org.opencds.cqf.cql.ls.server.validation.profile.ProfileValidatorLookup
import org.opencds.cqf.cql.ls.server.validation.profile.ProfileValidatorProvider
import org.opencds.cqf.cql.ls.server.validation.profile.ResourceProfileValidator
import java.nio.file.Files
import java.nio.file.Path

/** Fake engine: returns canned issues per file name and records what it was asked. */
private class FakeProfileValidator(
    private val issuesFor: (Path) -> List<ValidationIssue> = { emptyList() },
) : ResourceProfileValidator {
    val calls = mutableListOf<Triple<List<Path>, CaseResourceIndex, EngineMode>>()
    val checkReferencesSeen = mutableListOf<Boolean>()

    override fun validate(
        files: List<Path>,
        index: CaseResourceIndex,
        mode: EngineMode,
        checkReferences: Boolean,
    ): List<ValidationIssue> {
        calls.add(Triple(files, index, mode))
        checkReferencesSeen.add(checkReferences)
        return files.flatMap(issuesFor)
    }
}

private class FakeProvider(var lookup: ProfileValidatorLookup) : ProfileValidatorProvider {
    val requests = mutableListOf<Triple<Path, List<String>, EngineMode>>()

    override fun acquire(
        path: Path,
        packages: List<String>,
        mode: EngineMode,
    ): ProfileValidatorLookup {
        requests.add(Triple(path, packages, mode))
        return lookup
    }
}

class TestCaseValidatorProfileTest {
    private val patient = """{"resourceType": "Patient", "id": "pat-1"}"""

    // Encounter with a dangling reference and no status: two custom checks the engine covers
    private val encounter =
        """
        {
          "resourceType": "Encounter",
          "id": "enc-1",
          "class": {"code": "IMP"},
          "subject": {"reference": "Patient/pat-1"},
          "reasonReference": [{"reference": "Condition/missing"}]
        }
        """

    private fun writeCase(
        tempDir: Path,
        vararg files: Pair<String, String>,
    ): Path {
        val caseDir = tempDir.resolve("suite/case-1")
        Files.createDirectories(caseDir)
        files.forEach { (name, content) -> Files.writeString(caseDir.resolve(name), content.trimIndent()) }
        return caseDir
    }

    private fun core(
        code: String,
        file: Path,
        severity: ValidationSeverity = ValidationSeverity.ERROR,
        message: String = "core says $code",
    ) = ValidationIssue(severity = severity, code = code, message = message, filePath = file.toString(), line = 3, source = IssueSource.CORE)

    private fun validate(
        provider: ProfileValidatorProvider?,
        caseDir: Path,
        options: ValidateTestCaseOptions,
    ) = TestCaseValidator(provider).validate(ValidateTestCaseRequest(caseDir.toString(), options))

    @Test
    fun `profile OFF never asks the provider and reports OFF`(
        @TempDir tempDir: Path,
    ) {
        val caseDir = writeCase(tempDir, "Patient.json" to patient, "Encounter.json" to encounter)
        val provider = FakeProvider(ProfileValidatorLookup(ProfileEngineState.READY, FakeProfileValidator()))
        val response = validate(provider, caseDir, ValidateTestCaseOptions())
        assertTrue(provider.requests.isEmpty())
        assertEquals(ProfileStatus.OFF, response.profileStatus)
        assertEquals(ProfileStatus.OFF, response.testCases.single().profileStatus)
        assertTrue(response.testCases.single().issues.all { it.source == IssueSource.CUSTOM })
    }

    @Test
    fun `ready engine results merge as core and replace the covered custom checks`(
        @TempDir tempDir: Path,
    ) {
        val caseDir = writeCase(tempDir, "Patient.json" to patient, "Encounter.json" to encounter)
        val encounterFile = caseDir.resolve("Encounter.json")
        val engine =
            FakeProfileValidator { file ->
                if (file == encounterFile) {
                    listOf(core("Reference_REF_CantResolve", file), core("Validation_VAL_Profile_Minimum", file))
                } else {
                    emptyList()
                }
            }
        val provider = FakeProvider(ProfileValidatorLookup(ProfileEngineState.READY, engine))
        val response =
            validate(provider, caseDir, ValidateTestCaseOptions(profile = ProfileMode.FULL, packages = listOf("hl7.fhir.us.qicore#6.0.0")))

        val result = response.testCases.single()
        val codes = result.issues.map { it.code }
        assertFalse("ERR_DANGLING_REFERENCE" in codes, "covered by core: $codes")
        assertFalse("ERR_MISSING_REQUIRED_FIELD" in codes, "covered by core: $codes")
        assertTrue(result.issues.filter { it.source == IssueSource.CORE }.map { it.code }.containsAll(listOf("Reference_REF_CantResolve", "Validation_VAL_Profile_Minimum")))
        assertEquals(2, result.errorCount)
        assertEquals(ProfileStatus.RAN, result.profileStatus)
        assertEquals(ProfileStatus.RAN, response.profileStatus)

        val (files, index, mode) = engine.calls.single()
        assertEquals(setOf(caseDir.resolve("Patient.json"), encounterFile), files.toSet())
        assertEquals(EngineMode.FULL, mode)
        assertTrue("Patient/pat-1" in index.keys && "Encounter/enc-1" in index.keys)
        assertEquals(listOf("hl7.fhir.us.qicore#6.0.0"), provider.requests.single().second)
    }

    @Test
    fun `shared resources are indexed but not validated`(
        @TempDir tempDir: Path,
    ) {
        val caseDir = writeCase(tempDir, "Patient.json" to patient)
        val shared = tempDir.resolve("suite/shared")
        Files.createDirectories(shared)
        Files.writeString(shared.resolve("Org.json"), """{"resourceType": "Organization", "id": "org-1"}""")
        val engine = FakeProfileValidator()
        validate(FakeProvider(ProfileValidatorLookup(ProfileEngineState.READY, engine)), caseDir, ValidateTestCaseOptions(profile = ProfileMode.QUICK))
        val (files, index, _) = engine.calls.single()
        assertEquals(listOf(caseDir.resolve("Patient.json")), files)
        assertTrue("Organization/org-1" in index.keys)
    }

    @Test
    fun `custom invalid JSON is kept and the file is not sent to the engine`(
        @TempDir tempDir: Path,
    ) {
        val caseDir = writeCase(tempDir, "Patient.json" to patient, "Broken.json" to "{\"resourceType\": \"Observation\",")
        val engine = FakeProfileValidator()
        val result =
            validate(FakeProvider(ProfileValidatorLookup(ProfileEngineState.READY, engine)), caseDir, ValidateTestCaseOptions(profile = ProfileMode.QUICK))
                .testCases.single()
        assertEquals(listOf(caseDir.resolve("Patient.json")), engine.calls.single().first)
        assertEquals(1, result.issues.count { it.code == "ERR_INVALID_JSON" })
    }

    @Test
    fun `quick suppresses and downgrades, counting suppressed issues`(
        @TempDir tempDir: Path,
    ) {
        val caseDir = writeCase(tempDir, "Patient.json" to patient)
        val p = caseDir.resolve("Patient.json")
        val engine =
            FakeProfileValidator {
                listOf(
                    core("Terminology_PassThrough_TX_Message", it, ValidationSeverity.WARNING),
                    core("UNKNOWN_CODESYSTEM", it, ValidationSeverity.WARNING),
                    core("us-core-practitioner#us-core-17", it),
                    core("Some_Rule", it),
                )
            }
        val result =
            validate(
                FakeProvider(ProfileValidatorLookup(ProfileEngineState.READY, engine)),
                caseDir,
                ValidateTestCaseOptions(profile = ProfileMode.QUICK, suppressMessageIds = listOf("UNKNOWN_CODESYSTEM"), downgradeMessageIds = listOf("Some_Rule")),
            ).testCases.single()
        val coreIssues = result.issues.filter { it.source == IssueSource.CORE && it.filePath == p.toString() }
        assertEquals(listOf("us-core-practitioner#us-core-17", "Some_Rule"), coreIssues.map { it.code })
        assertTrue(coreIssues.all { it.severity == ValidationSeverity.WARNING })
        assertEquals(2, result.suppressedCount)
    }

    @Test
    fun `warming falls back to custom checks only with the notice`(
        @TempDir tempDir: Path,
    ) {
        val caseDir = writeCase(tempDir, "Patient.json" to patient, "Encounter.json" to encounter)
        val provider = FakeProvider(ProfileValidatorLookup(ProfileEngineState.WARMING))
        val response = validate(provider, caseDir, ValidateTestCaseOptions(profile = ProfileMode.QUICK))
        val result = response.testCases.single()
        assertEquals(ProfileStatus.WARMING, response.profileStatus)
        assertEquals(TestCaseValidator.WARMING_MESSAGE, response.profileMessage)
        assertEquals(ProfileStatus.WARMING, result.profileStatus)
        assertTrue(result.issues.any { it.code == "ERR_DANGLING_REFERENCE" }, "custom checks stay while warming")
        assertTrue(result.issues.none { it.source == IssueSource.CORE })
        assertEquals(EngineMode.QUICK, provider.requests.single().third)
    }

    @Test
    fun `failed engine reports FAILED with its message and keeps custom checks`(
        @TempDir tempDir: Path,
    ) {
        val caseDir = writeCase(tempDir, "Patient.json" to patient, "Encounter.json" to encounter)
        val provider = FakeProvider(ProfileValidatorLookup(ProfileEngineState.FAILED, message = "Profile validation unavailable: no network"))
        val response = validate(provider, caseDir, ValidateTestCaseOptions(profile = ProfileMode.FULL))
        assertEquals(ProfileStatus.FAILED, response.profileStatus)
        assertEquals("Profile validation unavailable: no network", response.profileMessage)
        assertTrue(response.testCases.single().issues.any { it.code == "ERR_DANGLING_REFERENCE" })
    }

    @Test
    fun `no provider configured reports FAILED`(
        @TempDir tempDir: Path,
    ) {
        val caseDir = writeCase(tempDir, "Patient.json" to patient)
        val response = validate(null, caseDir, ValidateTestCaseOptions(profile = ProfileMode.QUICK))
        assertEquals(ProfileStatus.FAILED, response.profileStatus)
        assertTrue(response.profileMessage!!.contains("not available"))
    }

    @Test
    fun `engine exception on a file keeps that file's custom checks`(
        @TempDir tempDir: Path,
    ) {
        val caseDir = writeCase(tempDir, "Patient.json" to patient, "Encounter.json" to encounter)
        val engine = FakeProfileValidator { if (it.fileName.toString() == "Encounter.json") listOf(core("CORE_VALIDATION_FAILED", it, ValidationSeverity.WARNING)) else emptyList() }
        val result =
            validate(FakeProvider(ProfileValidatorLookup(ProfileEngineState.READY, engine)), caseDir, ValidateTestCaseOptions(profile = ProfileMode.QUICK))
                .testCases.single()
        assertTrue(result.issues.any { it.code == "ERR_DANGLING_REFERENCE" })
        assertTrue(result.issues.any { it.code == "CORE_VALIDATION_FAILED" })
    }

    @Test
    fun `unknown profile sets the one-time hint on the case and the response`(
        @TempDir tempDir: Path,
    ) {
        val caseDir = writeCase(tempDir, "Patient.json" to patient)
        val url = "http://hl7.org/fhir/us/qicore/StructureDefinition/qicore-patient"
        val engine =
            FakeProfileValidator {
                listOf(core("VALIDATION_VAL_PROFILE_UNKNOWN_NOT_POLICY", it, ValidationSeverity.WARNING, "Profile reference '$url' has not been checked because it could not be found"))
            }
        val response = validate(FakeProvider(ProfileValidatorLookup(ProfileEngineState.READY, engine)), caseDir, ValidateTestCaseOptions(profile = ProfileMode.QUICK))
        val hint = "Profile $url isn't loaded; add its package to validation.packages"
        assertEquals(hint, response.testCases.single().profileMessage)
        assertEquals(hint, response.profileMessage)
    }

    @Test
    fun `ran without unknown profiles has no message`(
        @TempDir tempDir: Path,
    ) {
        val caseDir = writeCase(tempDir, "Patient.json" to patient)
        val response =
            validate(FakeProvider(ProfileValidatorLookup(ProfileEngineState.READY, FakeProfileValidator())), caseDir, ValidateTestCaseOptions(profile = ProfileMode.QUICK))
        assertNull(response.profileMessage)
        assertEquals(0, response.testCases.single().suppressedCount)
    }

    @Test
    fun `checkReferences reaches the engine`(
        @TempDir tempDir: Path,
    ) {
        val caseDir = writeCase(tempDir, "Patient.json" to patient, "Encounter.json" to encounter)
        val engine = FakeProfileValidator()
        val provider = FakeProvider(ProfileValidatorLookup(ProfileEngineState.READY, engine))
        validate(provider, caseDir, ValidateTestCaseOptions(profile = ProfileMode.QUICK))
        validate(provider, caseDir, ValidateTestCaseOptions(profile = ProfileMode.QUICK, checkReferences = false))
        assertEquals(listOf(true, false), engine.checkReferencesSeen)
    }
}
