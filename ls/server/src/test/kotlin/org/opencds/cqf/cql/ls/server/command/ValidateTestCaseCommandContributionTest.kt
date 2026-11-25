package org.opencds.cqf.cql.ls.server.command

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.eclipse.lsp4j.ExecuteCommandParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.cql.ls.server.validation.profile.CaseResourceIndex
import org.opencds.cqf.cql.ls.server.validation.profile.EngineMode
import org.opencds.cqf.cql.ls.server.validation.profile.ProfileEngineSpec
import org.opencds.cqf.cql.ls.server.validation.profile.ProfileIgInfo
import org.opencds.cqf.cql.ls.server.validation.profile.ProfileValidatorRegistry
import org.opencds.cqf.cql.ls.server.validation.profile.ResourceProfileValidator
import org.opencds.cqf.cql.ls.server.validation.testcase.PrewarmProfileValidationResponse
import org.opencds.cqf.cql.ls.server.validation.testcase.ProfileMode
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidateTestCaseOptions
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidateTestCaseRequest
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidateTestCaseResponse
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidationIssue
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidationSeverity
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException

class ValidateTestCaseCommandContributionTest {
    private val contribution = ValidateTestCaseCommandContribution()

    @Test
    fun `getCommands returns validateTestCase command`() {
        assertEquals(
            setOf("org.opencds.cqf.cql.ls.validateTestCase", "org.opencds.cqf.cql.ls.prewarmProfileValidation"),
            contribution.getCommands(),
        )
    }

    @Test
    fun `validateTestCase on non-existent path returns error`() {
        val params =
            ExecuteCommandParams(
                "org.opencds.cqf.cql.ls.validateTestCase",
                listOf(Gson().toJsonTree(ValidateTestCaseRequest(path = "/non/existent/path"))),
            )
        val future = contribution.executeCommand(params)
        val response = future.get() as ValidateTestCaseResponse
        assertEquals(1, response.errorCount)
        assertEquals("ERR_PATH_NOT_FOUND", response.testCases.first().issues.first().code)
    }

    @Test
    fun `validateTestCase happy path with linked Patient and Encounter`(
        @TempDir tempDir: Path,
    ) {
        val testCaseDir = tempDir.resolve("patient-1")
        Files.createDirectories(testCaseDir)

        Files.writeString(
            testCaseDir.resolve("Patient-1.json"),
            """
            {
              "resourceType": "Patient",
              "id": "patient-1",
              "name": [{"family": "Smith", "given": ["John"]}]
            }
            """.trimIndent(),
        )

        Files.writeString(
            testCaseDir.resolve("Encounter-1.json"),
            """
            {
              "resourceType": "Encounter",
              "id": "enc-1",
              "status": "finished",
              "class": {"code": "AMB"},
              "subject": {"reference": "Patient/patient-1"}
            }
            """.trimIndent(),
        )

        val params =
            ExecuteCommandParams(
                "org.opencds.cqf.cql.ls.validateTestCase",
                listOf(Gson().toJsonTree(ValidateTestCaseRequest(path = testCaseDir.toString()))),
            )
        val response = contribution.executeCommand(params).get() as ValidateTestCaseResponse

        assertEquals(1, response.testCaseCount)
        assertEquals(2, response.resourceCount)
        assertEquals(0, response.errorCount, "Expected 0 errors, got: ${response.testCases.first().issues}")
        assertEquals(0, response.warningCount, "Expected 0 warnings, got: ${response.testCases.first().issues}")
    }

    @Test
    fun `validateTestCase detects duplicate IDs, patient mismatch, and dangling references`(
        @TempDir tempDir: Path,
    ) {
        val testCaseDir = tempDir.resolve("patient-dup")
        Files.createDirectories(testCaseDir)

        Files.writeString(
            testCaseDir.resolve("Patient-1.json"),
            """
            {
              "resourceType": "Patient",
              "id": "pat-1"
            }
            """.trimIndent(),
        )

        // Duplicate Encounter id
        Files.writeString(
            testCaseDir.resolve("Encounter-1.json"),
            """
            {
              "resourceType": "Encounter",
              "id": "enc-1",
              "status": "finished",
              "class": {"code": "AMB"},
              "subject": {"reference": "Patient/pat-1"}
            }
            """.trimIndent(),
        )

        Files.writeString(
            testCaseDir.resolve("Encounter-1-dup.json"),
            """
            {
              "resourceType": "Encounter",
              "id": "enc-1",
              "status": "finished",
              "class": {"code": "AMB"},
              "subject": {"reference": "Patient/wrong-pat"},
              "serviceProvider": {"reference": "Organization/missing-org"}
            }
            """.trimIndent(),
        )

        // Condition with missing clinicalStatus
        Files.writeString(
            testCaseDir.resolve("Condition-1.json"),
            """
            {
              "resourceType": "Condition",
              "id": "cond-1",
              "subject": {"reference": "Patient/pat-1"}
            }
            """.trimIndent(),
        )

        val params =
            ExecuteCommandParams(
                "org.opencds.cqf.cql.ls.validateTestCase",
                listOf(Gson().toJsonTree(ValidateTestCaseRequest(path = testCaseDir.toString()))),
            )
        val response = contribution.executeCommand(params).get() as ValidateTestCaseResponse

        val issues = response.testCases.first().issues
        val codes = issues.map { it.code }.toSet()

        assertTrue(codes.contains("ERR_DUPLICATE_ID"), "Expected ERR_DUPLICATE_ID in $codes")
        assertTrue(codes.contains("ERR_PATIENT_MISMATCH"), "Expected ERR_PATIENT_MISMATCH in $codes")
        assertTrue(codes.contains("ERR_DANGLING_REFERENCE"), "Expected ERR_DANGLING_REFERENCE in $codes")
        assertTrue(codes.contains("WARN_MISSING_REQUIRED_FIELD"), "Expected WARN_MISSING_REQUIRED_FIELD in $codes")
    }

    @Test
    fun `validateTestCase resolves shared directory references`(
        @TempDir tempDir: Path,
    ) {
        val suiteDir = tempDir.resolve("MyMeasure")
        val sharedDir = suiteDir.resolve("shared")
        val caseDir = suiteDir.resolve("case-1")
        Files.createDirectories(sharedDir)
        Files.createDirectories(caseDir)

        Files.writeString(
            sharedDir.resolve("Practitioner-doc1.json"),
            """
            {
              "resourceType": "Practitioner",
              "id": "doc-1"
            }
            """.trimIndent(),
        )

        Files.writeString(
            caseDir.resolve("Patient-1.json"),
            """
            {
              "resourceType": "Patient",
              "id": "pat-1"
            }
            """.trimIndent(),
        )

        Files.writeString(
            caseDir.resolve("Encounter-1.json"),
            """
            {
              "resourceType": "Encounter",
              "id": "enc-1",
              "status": "finished",
              "class": {"code": "AMB"},
              "subject": {"reference": "Patient/pat-1"},
              "participant": [
                {"individual": {"reference": "Practitioner/doc-1"}}
              ]
            }
            """.trimIndent(),
        )

        val params =
            ExecuteCommandParams(
                "org.opencds.cqf.cql.ls.validateTestCase",
                listOf(Gson().toJsonTree(ValidateTestCaseRequest(path = caseDir.toString()))),
            )
        val response = contribution.executeCommand(params).get() as ValidateTestCaseResponse
        assertEquals(0, response.errorCount, "Shared Practitioner should resolve cleanly without errors: ${response.testCases.first().issues}")
    }

    @Test
    fun `validateTestCase with empty arguments returns failed future`() {
        val future = contribution.executeCommand(ExecuteCommandParams("org.opencds.cqf.cql.ls.validateTestCase", emptyList()))
        val ex = assertThrows(ExecutionException::class.java) { future.get() }
        assertInstanceOf(IllegalArgumentException::class.java, ex.cause)
    }

    @Test
    fun `validateTestCase with missing path returns failed future`() {
        val args = JsonObject().apply { add("options", JsonObject()) }
        val future = contribution.executeCommand(ExecuteCommandParams("org.opencds.cqf.cql.ls.validateTestCase", listOf(args)))
        val ex = assertThrows(ExecutionException::class.java) { future.get() }
        assertInstanceOf(IllegalArgumentException::class.java, ex.cause)
    }

    @Test
    fun `validateTestCase with blank path returns failed future`() {
        val future =
            contribution.executeCommand(
                ExecuteCommandParams("org.opencds.cqf.cql.ls.validateTestCase", listOf(JsonPrimitive("  "))),
            )
        val ex = assertThrows(ExecutionException::class.java) { future.get() }
        assertInstanceOf(IllegalArgumentException::class.java, ex.cause)
    }

    private fun prewarm(
        target: ValidateTestCaseCommandContribution,
        arg: Any,
    ) = target.executeCommand(ExecuteCommandParams("org.opencds.cqf.cql.ls.prewarmProfileValidation", listOf(arg)))

    @Test
    fun `prewarm starts a background build with the configured packages and answers straight away`(
        @TempDir tempDir: Path,
    ) {
        Files.writeString(tempDir.resolve("ig.ini"), "[IG]\n")
        val specs = mutableListOf<ProfileEngineSpec>()
        val pending = mutableListOf<Runnable>()
        val registry =
            ProfileValidatorRegistry(
                object : ProfileIgInfo {
                    override fun dependsOn(igRoot: Path) = listOf("hl7.terminology.r4#6.5.0")

                    override fun generation(igRoot: Path) = 0L
                },
                builder = { spec ->
                    specs.add(spec)
                    object : ResourceProfileValidator {
                        override fun validate(
                            files: List<Path>,
                            index: CaseResourceIndex,
                            mode: EngineMode,
                            checkReferences: Boolean,
                        ) = emptyList<ValidationIssue>()
                    }
                },
                executor =
                    object : java.util.concurrent.AbstractExecutorService() {
                        override fun execute(command: Runnable) {
                            pending.add(command)
                        }

                        override fun shutdown() = Unit

                        override fun shutdownNow() = mutableListOf<Runnable>()

                        override fun isShutdown() = false

                        override fun isTerminated() = false

                        override fun awaitTermination(
                            timeout: Long,
                            unit: java.util.concurrent.TimeUnit,
                        ) = true
                    },
            )
        val target = ValidateTestCaseCommandContribution(registry)
        val args =
            JsonObject().apply {
                addProperty("path", tempDir.resolve("input/tests").toString())
                add("packages", JsonArray().apply { add("hl7.fhir.us.qicore#6.0.0") })
            }

        val first = prewarm(target, args).get() as PrewarmProfileValidationResponse
        assertEquals("WARMING", first.status)
        assertTrue(specs.isEmpty(), "the build must not run on the request thread")
        pending.forEach { it.run() }
        assertEquals(listOf("hl7.terminology.r4#6.5.0", "hl7.fhir.us.qicore#6.0.0"), specs.single().packages)
        assertEquals("READY", (prewarm(target, args).get() as PrewarmProfileValidationResponse).status)
    }

    @Test
    fun `prewarm without a registry reports FAILED`() {
        val response = prewarm(contribution, JsonObject().apply { addProperty("path", "/tmp/x") }).get() as PrewarmProfileValidationResponse
        assertEquals("FAILED", response.status)
    }

    @Test
    fun `prewarm without a string path returns failed future`() {
        for (arg in listOf<Any>(JsonObject(), JsonObject().apply { addProperty("path", 3) }, JsonPrimitive("/tmp/x"))) {
            val ex = assertThrows(ExecutionException::class.java) { prewarm(contribution, arg).get() }
            assertInstanceOf(IllegalArgumentException::class.java, ex.cause, arg.toString())
        }
    }

    @Test
    fun `validateTestCase deserializes the new options and serializes source and profile status`(
        @TempDir tempDir: Path,
    ) {
        val caseDir = tempDir.resolve("case-1")
        Files.createDirectories(caseDir)
        Files.writeString(caseDir.resolve("Patient.json"), """{"resourceType": "Patient", "id": "p1"}""")
        val args =
            Gson().toJsonTree(
                mapOf(
                    "path" to caseDir.toString(),
                    "options" to mapOf("profile" to "QUICK", "packages" to listOf("a#1"), "suppressMessageIds" to listOf("X"), "downgradeMessageIds" to listOf("Y")),
                ),
            )
        val response = contribution.executeCommand(ExecuteCommandParams("org.opencds.cqf.cql.ls.validateTestCase", listOf(args))).get() as ValidateTestCaseResponse
        // No registry in this contribution: profile validation is requested but unavailable
        val json = Gson().toJsonTree(response).asJsonObject
        assertEquals("FAILED", json.get("profileStatus").asString)
        assertEquals("FAILED", json.getAsJsonArray("testCases")[0].asJsonObject.get("profileStatus").asString)
        assertEquals(0, json.getAsJsonArray("testCases")[0].asJsonObject.get("suppressedCount").asInt)
        val parsed = Gson().fromJson(args.asJsonObject.get("options"), ValidateTestCaseOptions::class.java)
        assertEquals(ProfileMode.QUICK, parsed.profile)
        assertEquals(listOf("a#1"), parsed.packages)
        val issueJson = Gson().toJsonTree(ValidationIssue(ValidationSeverity.ERROR, "C", "m"))
        assertEquals("custom", issueJson.asJsonObject.get("source").asString)
    }

    @Test
    fun `validateTestCase runs on the contribution's executor, not the common pool`(
        @TempDir tempDir: Path,
    ) {
        Files.writeString(tempDir.resolve("Patient.json"), """{"resourceType": "Patient", "id": "p1"}""")
        val ranOn = java.util.concurrent.atomic.AtomicReference<String>()
        val target =
            ValidateTestCaseCommandContribution(
                executor = { r ->
                    Thread {
                        ranOn.set("injected")
                        r.run()
                    }.start()
                },
            )
        target.executeCommand(ExecuteCommandParams("org.opencds.cqf.cql.ls.validateTestCase", listOf(JsonPrimitive(tempDir.toString())))).get()
        assertEquals("injected", ranOn.get())
    }
}
