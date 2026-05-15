package org.opencds.cqf.cql.debug

data class ExpressionSnapshot(
    val name: String,
    val value: String,
    val sourceUri: String,
    val startLine: Int,
    val startChar: Int,
    val endLine: Int,
    val endChar: Int,
)

data class DebugLaunchArgs(
    val libraryUri: String,
    val libraryName: String,
    val fhirVersion: String,
    val testCaseName: String? = null,
    val testCaseUri: String? = null,
    val terminologyUri: String? = null,
    val rootDir: String? = null,
    val optionsPath: String? = null,
)
