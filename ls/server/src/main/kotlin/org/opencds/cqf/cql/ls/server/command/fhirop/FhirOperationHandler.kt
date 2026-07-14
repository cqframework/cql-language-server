package org.opencds.cqf.cql.ls.server.command.fhirop

import java.util.concurrent.CompletableFuture

interface FhirOperationHandler {
    /** e.g. "PlanDefinition/$apply" — the key this handler is registered under. */
    val operationId: String

    /**
     * Runs the operation. Returns a future so handlers can offload heavy CQL/terminology work
     * off the LSP request thread.
     */
    fun run(
        request: FhirOperationRequest,
        ctx: FhirOperationContext,
    ): CompletableFuture<FhirOperationResponse>
}
