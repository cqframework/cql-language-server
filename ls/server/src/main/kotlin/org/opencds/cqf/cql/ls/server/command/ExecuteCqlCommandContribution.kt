package org.opencds.cqf.cql.ls.server.command

import com.google.gson.Gson
import com.google.gson.JsonElement
import org.eclipse.lsp4j.ExecuteCommandParams
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

class ExecuteCqlCommandContribution(
    private val igContextManager: IgContextManager,
    private val contentService: ContentService,
) : CommandContribution {
    companion object {
        private val log = LoggerFactory.getLogger(ExecuteCqlCommandContribution::class.java)
        const val EXECUTE_CQL_COMMAND = "org.opencds.cqf.cql.ls.executeCql"
    }

    override fun getCommands(): Set<String> = setOf(EXECUTE_CQL_COMMAND)

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        return if (EXECUTE_CQL_COMMAND == params.command) {
            log.debug("executeCql: received")
            val element = params.arguments[0] as JsonElement
            val request = Gson().fromJson(element, ExecuteCqlRequest::class.java)
            log.debug("executeCql: evaluating {} libraries", request.libraries.size)
            val result = CqlEvaluator.evaluate(request, contentService, igContextManager)
            log.debug("executeCql: done")
            CompletableFuture.completedFuture(result)
        } else {
            super.executeCommand(params)
        }
    }
}
