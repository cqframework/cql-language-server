package org.opencds.cqf.cql.ls.plugin.debug

import org.eclipse.lsp4j.ExecuteCommandParams
import org.opencds.cqf.cql.ls.plugin.debug.session.DebugSession
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution
import org.opencds.cqf.cql.ls.server.utility.Futures
import java.util.concurrent.CompletableFuture

class DebugCommandContribution(
    private val cqlCompilationManager: CqlCompilationManager,
) : CommandContribution {
    companion object {
        const val START_DEBUG_COMMAND = "org.opencds.cqf.cql.ls.plugin.debug.startDebugSession"
    }

    private var debugSession: DebugSession? = null

    override fun getCommands(): Set<String> {
        return setOf(START_DEBUG_COMMAND)
    }

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        if (START_DEBUG_COMMAND == params.command) {
            val session = debugSession
            if (session == null || !session.isActive()) {
                val newSession = DebugSession()
                debugSession = newSession
                return try {
                    CompletableFuture.completedFuture(newSession.start().get())
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Futures.failed(e)
                } catch (e: Exception) {
                    Futures.failed(e)
                }
            } else {
                throw IllegalStateException(
                    "Please wait for the current debug session to end before starting a new one.",
                )
            }
        } else {
            throw IllegalArgumentException(
                "DebugPlugin doesn't support command ${params.command}",
            )
        }
    }
}
