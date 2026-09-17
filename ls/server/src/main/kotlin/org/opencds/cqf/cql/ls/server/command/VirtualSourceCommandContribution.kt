package org.opencds.cqf.cql.ls.server.command

import com.google.gson.JsonElement
import org.eclipse.lsp4j.ExecuteCommandParams
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * Serves CQL source text for `cql-virtual:` URIs — synthetic URIs minted by
 * [org.opencds.cqf.cql.ls.server.provider.DefinitionProvider] when go-to-definition resolves a
 * library with no workspace file (bundled, e.g. FHIRHelpers, or npm-installed, e.g. FHIRCommon).
 * The vscode-cql extension registers a `TextDocumentContentProvider` for the `cql-virtual` scheme
 * that calls this command to fetch content for the read-only tab VS Code opens.
 */
class VirtualSourceCommandContribution(
    private val cqlCompilationManager: CqlCompilationManager,
) : CommandContribution {
    companion object {
        private val log = LoggerFactory.getLogger(VirtualSourceCommandContribution::class.java)
        private const val VIRTUAL_SOURCE_COMMAND = "org.opencds.cqf.cql.ls.virtualSource"
    }

    override fun getCommands(): Set<String> = setOf(VIRTUAL_SOURCE_COMMAND)

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        return when (params.command) {
            VIRTUAL_SOURCE_COMMAND -> virtualSource(params)
            else -> super.executeCommand(params)
        }
    }

    private fun virtualSource(params: ExecuteCommandParams): CompletableFuture<Any> {
        val args = params.arguments
        if (args == null || args.isEmpty()) {
            log.debug("virtualSource: no arguments provided")
            return CompletableFuture.completedFuture(null)
        }

        val uriString =
            (args[0] as? JsonElement)?.asString
                ?: run {
                    log.debug("virtualSource: args[0] is not a JsonElement string (type={})", args[0]?.javaClass?.simpleName)
                    return CompletableFuture.completedFuture(null)
                }

        val uri =
            Uris.parseOrNull(uriString)
                ?: run {
                    log.debug("virtualSource: could not parse URI '{}'", uriString)
                    return CompletableFuture.completedFuture(null)
                }

        val content = cqlCompilationManager.getSourceText(uri)
        log.debug("virtualSource: uri={} returning {}", uri, if (content != null) "content, length=${content.length}" else "null")
        return CompletableFuture.completedFuture(content)
    }
}
