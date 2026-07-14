package org.opencds.cqf.cql.ls.server.command.fhirop

import com.google.gson.Gson
import com.google.gson.JsonElement
import org.eclipse.lsp4j.ExecuteCommandParams
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * Single [CommandContribution] for all generic FHIR operations ($apply, and future operations
 * like $populate/$extract). Dispatches to a registered [FhirOperationHandler] by
 * [FhirOperationRequest.operation]. Adding a new operation is one new handler + one registry
 * entry here — no new LSP command.
 */
class FhirOperationCommandContribution(
    private val ctx: FhirOperationContext,
    handlers: List<FhirOperationHandler>,
) : CommandContribution {
    companion object {
        private val log = LoggerFactory.getLogger(FhirOperationCommandContribution::class.java)
        const val FHIR_OPERATION_COMMAND = "org.opencds.cqf.cql.ls.fhirOperation"
    }

    private val registry: Map<String, FhirOperationHandler> = handlers.associateBy { it.operationId }

    override fun getCommands(): Set<String> = setOf(FHIR_OPERATION_COMMAND)

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        if (FHIR_OPERATION_COMMAND != params.command) {
            return super.executeCommand(params)
        }

        val element = params.arguments[0] as JsonElement
        val request = Gson().fromJson(element, FhirOperationRequest::class.java)
        log.debug("fhirOperation: received {}", request.operation)

        val handler = registry[request.operation]
        if (handler == null) {
            log.warn("fhirOperation: no handler registered for {}", request.operation)
            return CompletableFuture.completedFuture(
                FhirOperationResponse(
                    operation = request.operation,
                    errors = listOf("Unsupported operation: ${request.operation}"),
                ),
            )
        }

        @Suppress("UNCHECKED_CAST")
        return handler.run(request, ctx) as CompletableFuture<Any>
    }
}
