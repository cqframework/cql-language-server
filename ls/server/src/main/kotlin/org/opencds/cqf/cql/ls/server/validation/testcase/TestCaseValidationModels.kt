package org.opencds.cqf.cql.ls.server.validation.testcase

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
)

data class ValidateTestCaseResponse(
    val targetPath: String,
    val testCaseCount: Int,
    val resourceCount: Int,
    val errorCount: Int,
    val warningCount: Int,
    val testCases: List<TestCaseValidationResult>,
)

data class TestCaseValidationResult(
    val testCasePath: String,
    val testCaseName: String,
    val patientId: String?,
    val resourceCount: Int,
    val errorCount: Int,
    val warningCount: Int,
    val issues: List<ValidationIssue>,
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
)

enum class ValidationSeverity {
    ERROR,
    WARNING,
    INFORMATION,
}
