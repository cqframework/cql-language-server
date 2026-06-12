package org.opencds.cqf.cql.ls.server.command

import org.eclipse.lsp4j.ExecuteCommandParams
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution
import org.opencds.cqf.cql.ls.server.utility.VersionReader
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

class GetVersionInfoCommandContribution : CommandContribution {
    companion object {
        private val log = LoggerFactory.getLogger(GetVersionInfoCommandContribution::class.java)
        const val GET_VERSION_INFO_COMMAND = "org.opencds.cqf.cql.ls.getVersionInfo"
    }

    override fun getCommands(): Set<String> = setOf(GET_VERSION_INFO_COMMAND)

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        return if (GET_VERSION_INFO_COMMAND == params.command) {
            log.debug("getVersionInfo: received")
            val versions = VersionInfo(
                translator = VersionReader.loadVersion("cql-to-elm-jvm"),
                engine = VersionReader.loadVersion("engine-jvm"),
                clinicalReasoning = VersionReader.loadVersion("cqf-fhir-cql"),
                languageServer = VersionReader.loadVersion("cql-ls-server"),
            )
            log.debug("getVersionInfo: returning {}", versions)
            CompletableFuture.completedFuture(versions)
        } else {
            super.executeCommand(params)
        }
    }
}
