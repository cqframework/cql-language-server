package org.opencds.cqf.cql.ls.server.command.fhirop

data class FhirOperationRequest(
    val operation: String,
    val fhirVersion: String,
    val rootDir: String? = null,
    val resourceUri: String? = null,
    val resourceId: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val dataBundle: String? = null,
)
