package org.opencds.cqf.cql.ls.server.command

import com.google.gson.Gson
import com.google.gson.JsonElement
import org.eclipse.lsp4j.ExecuteCommandParams
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution
import org.opencds.cqf.cql.ls.server.validation.testcase.TestCaseValidator
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidateTestCaseRequest
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

class ValidateTestCaseCommandContribution : CommandContribution {
    companion object {
        private val log = LoggerFactory.getLogger(ValidateTestCaseCommandContribution::class.java)
        const val VALIDATE_TEST_CASE_COMMAND = "org.opencds.cqf.cql.ls.validateTestCase"
    }

    private val validator = TestCaseValidator()

    override fun getCommands(): Set<String> = setOf(VALIDATE_TEST_CASE_COMMAND)

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        return if (VALIDATE_TEST_CASE_COMMAND == params.command) {
            log.debug("validateTestCase: received command")
            val args = params.arguments
            if (args.isNullOrEmpty()) {
                throw IllegalArgumentException("validateTestCase: missing arguments")
            }

            val request =
                when (val firstArg = args[0]) {
                    is JsonElement -> {
                        if (firstArg.isJsonObject) {
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

            log.debug("validateTestCase: validating path {}", request.path)
            val response = validator.validate(request)
            log.debug("validateTestCase: finished with {} errors, {} warnings", response.errorCount, response.warningCount)
            CompletableFuture.completedFuture(response)
        } else {
            super.executeCommand(params)
        }
    }
}
