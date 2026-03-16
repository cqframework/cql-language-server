package org.opencds.cqf.cql.ls.server.plugin

import org.eclipse.lsp4j.ExecuteCommandParams
import java.util.concurrent.CompletableFuture

interface CommandContribution {
    fun getCommands(): Set<String> = emptySet()

    fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        throw RuntimeException("Unsupported Command: ${params.command}")
    }
}
