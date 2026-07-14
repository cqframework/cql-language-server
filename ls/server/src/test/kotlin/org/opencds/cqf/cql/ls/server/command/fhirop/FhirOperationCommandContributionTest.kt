package org.opencds.cqf.cql.ls.server.command.fhirop

import com.google.gson.Gson
import com.google.gson.JsonElement
import org.eclipse.lsp4j.ExecuteCommandParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.server.command.fhirop.handlers.ApplyPlanDefinitionHandler
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService

class FhirOperationCommandContributionTest {
    private val cs = TestContentService()
    private val ctx = FhirOperationContext(IgContextManager(cs), cs, LibraryResolutionManager(emptyList()))
    private val contribution = FhirOperationCommandContribution(ctx, listOf(ApplyPlanDefinitionHandler()))

    private fun paramsFor(request: FhirOperationRequest): ExecuteCommandParams {
        val json = Gson().toJsonTree(request) as JsonElement
        return ExecuteCommandParams(FhirOperationCommandContribution.FHIR_OPERATION_COMMAND, listOf(json))
    }

    @Test
    fun getCommands_returnsFhirOperationCommand() {
        assertEquals(setOf(FhirOperationCommandContribution.FHIR_OPERATION_COMMAND), contribution.getCommands())
    }

    @Test
    fun executeCommand_unknownOperation_returnsErrorsWithoutThrowing() {
        val request =
            FhirOperationRequest(
                operation = "Questionnaire/\$populate",
                fhirVersion = "R4",
            )

        val response = contribution.executeCommand(paramsFor(request)).get() as FhirOperationResponse

        assertTrue(response.errors.isNotEmpty(), "Expected an error for an unregistered operation")
        assertEquals("Questionnaire/\$populate", response.operation)
    }

    @Test
    fun executeCommand_knownOperation_dispatchesToHandler() {
        val request =
            FhirOperationRequest(
                operation = "PlanDefinition/\$apply",
                fhirVersion = "R4",
                resourceUri = "/org/opencds/cqf/cql/ls/server/fhirop/PlanDefinition-apply-test.json",
                parameters = mapOf("subject" to "Patient/patient-1"),
            )

        val response = contribution.executeCommand(paramsFor(request)).get() as FhirOperationResponse

        assertTrue(response.errors.isEmpty(), "Expected no errors, got: ${response.errors}")
        assertEquals("PlanDefinition/\$apply", response.operation)
    }

    @Test
    fun executeCommand_unrecognizedCommand_throws() {
        assertThrows(RuntimeException::class.java) {
            contribution.executeCommand(ExecuteCommandParams("some.other.command", emptyList()))
        }
    }
}
