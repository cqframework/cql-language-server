package org.opencds.cqf.cql.ls.server.command.fhirop.handlers

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.cql.ls.server.command.fhirop.FhirOperationContext
import org.opencds.cqf.cql.ls.server.command.fhirop.FhirOperationRequest
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService
import java.nio.file.Files
import java.nio.file.Path

/**
 * Regression coverage for [ApplyPlanDefinitionHandler]'s repository resolution: `rootDir` is
 * always the project root sibling to `input/` (see [ApplyPlanDefinitionHandler.buildEvaluationSettings],
 * which already appends "input"), so [ApplyPlanDefinitionHandler.buildRepository] must append
 * the same "input" segment before handing the path to `IgStandardRepository`.
 */
class ApplyPlanDefinitionHandlerRootDirTest {
    private val cs = TestContentService()
    private val ctx = FhirOperationContext(IgContextManager(cs), cs, LibraryResolutionManager(emptyList()))
    private val handler = ApplyPlanDefinitionHandler()

    @Test
    fun run_resolvesPlanDefinitionByIdUnderInputDirectory(
        @TempDir tempDir: Path,
    ) {
        val planDefDir = tempDir.resolve("input/resources/plandefinition")
        Files.createDirectories(planDefDir)
        Files.writeString(
            planDefDir.resolve("rootdir-test.json"),
            """
            {
              "resourceType": "PlanDefinition",
              "id": "rootdir-test",
              "status": "active",
              "action": [
                { "id": "action-1", "title": "Test Action" }
              ]
            }
            """.trimIndent(),
        )

        val request =
            FhirOperationRequest(
                operation = "PlanDefinition/\$apply",
                fhirVersion = "R4",
                rootDir = tempDir.toUri().toString(),
                resourceId = "rootdir-test",
                parameters = mapOf("subject" to "Patient/patient-1"),
            )

        val response = handler.run(request, ctx).get()

        assertTrue(response.errors.isEmpty(), "Expected no errors, got: ${response.errors}")
        assertNotNull(response.resultJson, "Expected a serialized result resource")
    }
}
