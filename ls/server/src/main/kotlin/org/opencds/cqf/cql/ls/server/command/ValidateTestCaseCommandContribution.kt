package org.opencds.cqf.cql.ls.server.command

import com.google.gson.Gson
import com.google.gson.JsonElement
import org.eclipse.lsp4j.ExecuteCommandParams
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution
import org.opencds.cqf.cql.ls.server.validation.profile.ProfileValidatorRegistry
import org.opencds.cqf.cql.ls.server.validation.testcase.PrewarmProfileValidationResponse
import org.opencds.cqf.cql.ls.server.validation.testcase.TestCaseValidator
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidateTestCaseRequest
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ValidateTestCaseCommandContribution(
    private val profileValidators: ProfileValidatorRegistry? = null,
    // Validation can wait ~40 s on the engine lock for a suite; keep it off ForkJoinPool.commonPool
    private val executor: Executor = defaultExecutor(),
) : CommandContribution {
    companion object {
        private val log = LoggerFactory.getLogger(ValidateTestCaseCommandContribution::class.java)
        const val VALIDATE_TEST_CASE_COMMAND = "org.opencds.cqf.cql.ls.validateTestCase"
        const val PREWARM_PROFILE_VALIDATION_COMMAND = "org.opencds.cqf.cql.ls.prewarmProfileValidation"

        private fun defaultExecutor(): Executor {
            val count = AtomicInteger()
            return Executors.newFixedThreadPool(2) { r ->
                Thread(r, "test-case-validation-${count.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }

    private val validator = TestCaseValidator(profileValidators)

    override fun getCommands(): Set<String> = setOf(VALIDATE_TEST_CASE_COMMAND, PREWARM_PROFILE_VALIDATION_COMMAND)

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        if (PREWARM_PROFILE_VALIDATION_COMMAND == params.command) {
            return try {
                CompletableFuture.completedFuture(prewarm(params.arguments))
            } catch (e: Exception) {
                CompletableFuture.failedFuture(
                    e as? IllegalArgumentException ?: IllegalArgumentException("prewarmProfileValidation: invalid arguments: ${e.message}", e),
                )
            }
        }
        return if (VALIDATE_TEST_CASE_COMMAND == params.command) {
            log.debug("validateTestCase: received command")
            val request =
                try {
                    parseRequest(params.arguments)
                } catch (e: Exception) {
                    return CompletableFuture.failedFuture(
                        e as? IllegalArgumentException ?: IllegalArgumentException("validateTestCase: invalid arguments: ${e.message}", e),
                    )
                }

            log.debug("validateTestCase: validating path {}", request.path)
            CompletableFuture.supplyAsync<Any>({
                validator.validate(request).also {
                    log.debug("validateTestCase: finished with {} errors, {} warnings", it.errorCount, it.warningCount)
                }
            }, executor)
        } else {
            super.executeCommand(params)
        }
    }

    /** Starts the engine build in the background and answers straight away with its state. */
    private fun prewarm(args: List<Any?>?): PrewarmProfileValidationResponse {
        val obj =
            (args?.firstOrNull() as? JsonElement)?.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw IllegalArgumentException("prewarmProfileValidation: expected an object { path, packages }")
        val path =
            obj.get("path")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("prewarmProfileValidation: request is missing a string 'path'")
        val packages =
            obj.get("packages")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }
                .orEmpty()
        val registry =
            profileValidators
                ?: return PrewarmProfileValidationResponse("FAILED", "Profile validation is not available in this language server.")
        val lookup = registry.prewarm(TestCaseValidator.normalizePath(path), packages)
        log.debug("prewarmProfileValidation: {} -> {}", path, lookup.state)
        return PrewarmProfileValidationResponse(lookup.state.name, lookup.message)
    }

    private fun parseRequest(args: List<Any?>?): ValidateTestCaseRequest {
        if (args.isNullOrEmpty()) {
            throw IllegalArgumentException("validateTestCase: missing arguments")
        }

        val request =
            when (val firstArg = args[0]) {
                is JsonElement -> {
                    if (firstArg.isJsonObject) {
                        // Gson bypasses Kotlin null-safety, so check `path` on the JSON before deserializing
                        val pathElement = firstArg.asJsonObject.get("path")
                        if (pathElement == null || !pathElement.isJsonPrimitive || !pathElement.asJsonPrimitive.isString) {
                            throw IllegalArgumentException("validateTestCase: request is missing a string 'path'")
                        }
                        Gson().fromJson(firstArg, ValidateTestCaseRequest::class.java)
                    } else if (firstArg.isJsonPrimitive && firstArg.asJsonPrimitive.isString) {
                        ValidateTestCaseRequest(path = firstArg.asString)
                    } else {
                        throw IllegalArgumentException("validateTestCase: unexpected argument format $firstArg")
                    }
                }
                is String -> ValidateTestCaseRequest(path = firstArg)
                else -> throw IllegalArgumentException("validateTestCase: unsupported argument type ${firstArg?.javaClass?.name}")
            }

        if (request.path.isBlank()) {
            throw IllegalArgumentException("validateTestCase: 'path' must not be blank")
        }
        return request
    }
}
