package org.opencds.cqf.cql.ls.server.validation.testcase

import com.google.gson.annotations.SerializedName

data class ValidateTestCaseRequest(
    val path: String,
    val options: ValidateTestCaseOptions? = null,
)

data class ValidateTestCaseOptions(
    val checkReferences: Boolean = true,
    val checkDuplicateIds: Boolean = true,
    val checkPatientLinkage: Boolean = true,
    val checkRequiredFields: Boolean = true,
    val checkMadieStructure: Boolean = true,
    val sharedDirs: List<String>? = null,
    // Profile validation with the HL7 core engine; null means OFF
    val profile: ProfileMode? = null,
    val suppressMessageIds: List<String>? = null,
    val downgradeMessageIds: List<String>? = null,
    // `id#version` packages loaded on top of the IG's dependsOn
    val packages: List<String>? = null,
)

enum class ProfileMode {
    OFF,
    QUICK,
    FULL,
}

enum class ProfileStatus {
    OFF,
    RAN,
    WARMING,
    FAILED,
}

data class ValidateTestCaseResponse(
    val targetPath: String,
    val testCaseCount: Int,
    val resourceCount: Int,
    val errorCount: Int,
    val warningCount: Int,
    val testCases: List<TestCaseValidationResult>,
    val profileStatus: ProfileStatus = ProfileStatus.OFF,
    val profileMessage: String? = null,
)

data class TestCaseValidationResult(
    val testCasePath: String,
    val testCaseName: String,
    val patientId: String?,
    val resourceCount: Int,
    val errorCount: Int,
    val warningCount: Int,
    val issues: List<ValidationIssue>,
    val profileStatus: ProfileStatus = ProfileStatus.OFF,
    val profileMessage: String? = null,
    val suppressedCount: Int = 0,
)

data class ValidationIssue(
    val severity: ValidationSeverity,
    val code: String,
    val message: String,
    val resourceType: String? = null,
    val resourceId: String? = null,
    val filePath: String? = null,
    val jsonPath: String? = null,
    val line: Int? = null,
    val source: IssueSource = IssueSource.CUSTOM,
)

enum class IssueSource {
    @SerializedName("custom")
    CUSTOM,

    @SerializedName("core")
    CORE,
}

enum class ValidationSeverity {
    ERROR,
    WARNING,
    INFORMATION,
}

/** The prewarm command's answer; [status] is a [org.opencds.cqf.cql.ls.server.validation.profile.ProfileEngineState] name. */
data class PrewarmProfileValidationResponse(
    val status: String,
    val message: String? = null,
)
