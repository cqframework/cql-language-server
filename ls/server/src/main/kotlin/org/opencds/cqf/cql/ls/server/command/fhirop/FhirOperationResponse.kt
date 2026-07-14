package org.opencds.cqf.cql.ls.server.command.fhirop

data class FhirOperationResponse(
    val operation: String,
    val resultJson: String? = null,
    val resultType: String? = null,
    val errors: List<String> = emptyList(),
    val logs: List<String> = emptyList(),
)
