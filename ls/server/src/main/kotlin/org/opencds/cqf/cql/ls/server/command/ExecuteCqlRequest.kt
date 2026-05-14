package org.opencds.cqf.cql.ls.server.command

data class ExecuteCqlRequest(
    val fhirVersion: String,
    val rootDir: String?,
    val optionsPath: String?,
    val libraries: List<LibraryRequest>,
)

data class LibraryRequest(
    val libraryName: String,
    val libraryUri: String,
    val libraryVersion: String?,
    val terminologyUri: String?,
    val model: ModelRequest?,
    val context: ContextRequest?,
    // TODO: parameter passing deferred to PR #6 (Execute CQL optimization)
    val parameters: List<ParameterRequest>,
)

data class ModelRequest(val modelName: String, val modelUri: String)

data class ContextRequest(val contextName: String, val contextValue: String)

data class ParameterRequest(val parameterName: String, val parameterType: String, val parameterValue: String)

data class ExecuteCqlResponse(val results: List<LibraryResult>, val logs: List<String>)

data class LibraryResult(
    val libraryName: String,
    val expressions: List<ExpressionResult>,
    val usedDefaultParameters: List<DefaultParameterResult> = emptyList(),
)

data class ExpressionResult(val name: String, val value: String)

data class DefaultParameterResult(val name: String, val value: String)
