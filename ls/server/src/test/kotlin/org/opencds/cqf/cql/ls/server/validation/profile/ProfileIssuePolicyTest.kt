package org.opencds.cqf.cql.ls.server.validation.profile

import org.hl7.fhir.utilities.validation.ValidationMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.server.validation.testcase.IssueSource
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidationIssue
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidationSeverity
import java.nio.file.Paths

class ProfileIssuePolicyTest {
    private fun message(
        id: String?,
        text: String,
        level: ValidationMessage.IssueSeverity = ValidationMessage.IssueSeverity.ERROR,
        line: Int = 12,
    ) = ValidationMessage(
        ValidationMessage.Source.InstanceValidator,
        ValidationMessage.IssueType.INVALID,
        line,
        3,
        "Encounter.subject",
        text,
        level,
    ).apply { if (id != null) setMessageId(id) }

    private fun issue(
        code: String,
        severity: ValidationSeverity = ValidationSeverity.ERROR,
        message: String = "msg",
    ) = ValidationIssue(severity = severity, code = code, message = message, source = IssueSource.CORE)

    @Test
    fun `mapper carries severity, id, location, line and core source`() {
        val file = Paths.get("case", "Encounter.json")
        val mapped = message("Reference_REF_CantResolve", "Unable to resolve resource").toCoreIssue(file)
        assertEquals(ValidationSeverity.ERROR, mapped.severity)
        assertEquals("Reference_REF_CantResolve", mapped.code)
        assertEquals("Unable to resolve resource", mapped.message)
        assertEquals("Encounter.subject", mapped.jsonPath)
        assertEquals(12, mapped.line)
        assertEquals(file.toString(), mapped.filePath)
        assertEquals(IssueSource.CORE, mapped.source)
    }

    @Test
    fun `mapper maps fatal to error, information, missing id and unknown line`() {
        val file = Paths.get("x.json")
        assertEquals(ValidationSeverity.ERROR, message("a", "t", ValidationMessage.IssueSeverity.FATAL).toCoreIssue(file).severity)
        assertEquals(ValidationSeverity.WARNING, message("a", "t", ValidationMessage.IssueSeverity.WARNING).toCoreIssue(file).severity)
        assertEquals(ValidationSeverity.INFORMATION, message("a", "t", ValidationMessage.IssueSeverity.INFORMATION).toCoreIssue(file).severity)
        val noId = message(null, "Unable to validate code without using server", line = -1).toCoreIssue(file)
        assertEquals(ProfileIssuePolicy.NO_MESSAGE_ID, noId.code)
        assertNull(noId.line)
    }

    @Test
    fun `quick suppresses terminology noise by id and by message text and counts it`() {
        val issues =
            listOf(
                issue(ProfileIssuePolicy.NO_MESSAGE_ID, ValidationSeverity.WARNING, "Unable to validate code without using server because..."),
                issue("Error_validating_code_running_without_terminology_services", ValidationSeverity.WARNING),
                issue("Terminology_PassThrough_TX_Message", ValidationSeverity.WARNING),
                issue("Reference_REF_CantResolve"),
            )
        val quick = ProfileIssuePolicy(EngineMode.QUICK).apply(issues)
        assertEquals(listOf("Reference_REF_CantResolve"), quick.issues.map { it.code })
        assertEquals(3, quick.suppressedCount)

        val full = ProfileIssuePolicy(EngineMode.FULL).apply(issues)
        assertEquals(4, full.issues.size)
        assertEquals(0, full.suppressedCount)
    }

    @Test
    fun `quick drops informational messages, full keeps them`() {
        val issues = listOf(issue("Some_Info", ValidationSeverity.INFORMATION))
        assertEquals(1, ProfileIssuePolicy(EngineMode.QUICK).apply(issues).suppressedCount)
        assertEquals(1, ProfileIssuePolicy(EngineMode.FULL).apply(issues).issues.size)
    }

    @Test
    fun `default downgrades match invariant suffixes and DUPLICATE_ID`() {
        val result =
            ProfileIssuePolicy(EngineMode.QUICK).apply(
                listOf(
                    issue("http://hl7.org/fhir/us/core/StructureDefinition/us-core-practitioner#us-core-17"),
                    issue("us-core-16"),
                    issue("DUPLICATE_ID"),
                    issue("us-core-170"),
                ),
            )
        assertEquals(
            listOf(ValidationSeverity.WARNING, ValidationSeverity.WARNING, ValidationSeverity.WARNING, ValidationSeverity.ERROR),
            result.issues.map { it.severity },
        )
        assertEquals(0, result.suppressedCount)
    }

    @Test
    fun `user suppress and downgrade lists add to the defaults`() {
        val result =
            ProfileIssuePolicy(EngineMode.FULL, listOf("UNKNOWN_CODESYSTEM"), listOf("Validation_VAL_Profile_Minimum")).apply(
                listOf(
                    issue("UNKNOWN_CODESYSTEM", ValidationSeverity.WARNING),
                    issue("Validation_VAL_Profile_Minimum"),
                    issue("DUPLICATE_ID"),
                ),
            )
        assertEquals(listOf("Validation_VAL_Profile_Minimum", "DUPLICATE_ID"), result.issues.map { it.code })
        assertEquals(listOf(ValidationSeverity.WARNING, ValidationSeverity.WARNING), result.issues.map { it.severity })
        assertEquals(1, result.suppressedCount)
    }

    @Test
    fun `blank entries match nothing`() {
        val result = ProfileIssuePolicy(EngineMode.FULL, listOf("", " "), null).apply(listOf(issue("A")))
        assertEquals(1, result.issues.size)
    }

    @Test
    fun `unknown profile hint names the first url and counts the rest`() {
        val url1 = "http://hl7.org/fhir/us/qicore/StructureDefinition/qicore-condition"
        val url2 = "http://hl7.org/fhir/us/qicore/StructureDefinition/qicore-patient"
        val issues =
            listOf(
                issue("VALIDATION_VAL_PROFILE_UNKNOWN_NOT_POLICY", ValidationSeverity.WARNING, "Profile reference '$url1' has not been checked because it could not be found"),
                issue("Validation_VAL_Profile_Unknown", ValidationSeverity.ERROR, "Profile reference '$url2' could not be resolved, so has not been checked"),
                issue("Validation_VAL_Profile_Unknown", ValidationSeverity.ERROR, "Profile reference '$url1' could not be resolved"),
            )
        assertEquals(
            "Profile $url1 (and 1 more) isn't loaded; add its package to validation.packages",
            ProfileIssuePolicy.unknownProfileHint(issues),
        )
        assertEquals(
            "Profile $url2 isn't loaded; add its package to validation.packages",
            ProfileIssuePolicy.unknownProfileHint(issues.subList(1, 2)),
        )
        assertNull(ProfileIssuePolicy.unknownProfileHint(listOf(issue("Reference_REF_CantResolve"))))
        assertNull(ProfileIssuePolicy.unknownProfileHint(listOf(issues[0].copy(source = IssueSource.CUSTOM))))
    }
}
