package org.opencds.cqf.cql.ls.server.command.fhirop.handlers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.server.command.fhirop.FhirOperationContext
import org.opencds.cqf.cql.ls.server.command.fhirop.FhirOperationRequest
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService

class ApplyPlanDefinitionHandlerTest {
    private val cs = TestContentService()
    private val ctx = FhirOperationContext(IgContextManager(cs), cs, LibraryResolutionManager(emptyList()))
    private val handler = ApplyPlanDefinitionHandler()

    @Test
    fun run_appliesPlanDefinitionByUri_returnsCarePlan() {
        val request =
            FhirOperationRequest(
                operation = "PlanDefinition/\$apply",
                fhirVersion = "R4",
                resourceUri = "/org/opencds/cqf/cql/ls/server/fhirop/PlanDefinition-apply-test.json",
                parameters = mapOf("subject" to "Patient/patient-1"),
            )

        val response = handler.run(request, ctx).get()

        assertTrue(response.errors.isEmpty(), "Expected no errors, got: ${response.errors}")
        assertNotNull(response.resultJson, "Expected a serialized result resource")
        assertNotNull(response.resultType)
        assertTrue(
            response.resultJson!!.contains("CarePlan") || response.resultJson!!.contains("RequestOrchestration"),
            "Expected a CarePlan/RequestOrchestration result: ${response.resultJson}",
        )
    }

    @Test
    fun run_missingSubject_returnsError() {
        val request =
            FhirOperationRequest(
                operation = "PlanDefinition/\$apply",
                fhirVersion = "R4",
                resourceUri = "/org/opencds/cqf/cql/ls/server/fhirop/PlanDefinition-apply-test.json",
            )

        val response = handler.run(request, ctx).get()

        assertTrue(response.errors.isNotEmpty(), "Expected an error for a missing subject")
        assertNull(response.resultJson)
    }

    @Test
    fun run_missingResourceReference_returnsError() {
        val request =
            FhirOperationRequest(
                operation = "PlanDefinition/\$apply",
                fhirVersion = "R4",
                parameters = mapOf("subject" to "Patient/patient-1"),
            )

        val response = handler.run(request, ctx).get()

        assertTrue(response.errors.isNotEmpty(), "Expected an error when neither resourceUri nor resourceId is set")
        assertEquals("PlanDefinition/\$apply", response.operation)
    }

    @Test
    fun run_badDataBundle_returnsError() {
        val request =
            FhirOperationRequest(
                operation = "PlanDefinition/\$apply",
                fhirVersion = "R4",
                resourceUri = "/org/opencds/cqf/cql/ls/server/fhirop/PlanDefinition-apply-test.json",
                parameters = mapOf("subject" to "Patient/patient-1"),
                dataBundle = """{"resourceType": "Patient"}""",
            )

        val response = handler.run(request, ctx).get()

        assertTrue(response.errors.isNotEmpty(), "Expected an error when dataBundle is not a Bundle")
        assertNull(response.resultJson)
    }
}
