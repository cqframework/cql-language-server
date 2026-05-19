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

data class SubExpressionSnapshot(
    val value: String,
    val parentDefine: String,
    val startLine: Int,
    val startChar: Int,
    val endLine: Int,
    val endChar: Int,
) {
    fun contains(line: Int, col: Int): Boolean {
        if (line < startLine || line > endLine) return false
        if (line == startLine && col < startChar) return false
        if (line == endLine && col > endChar) return false
        return true
    }
}

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
