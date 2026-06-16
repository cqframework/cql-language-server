package org.opencds.cqf.cql.debug

import org.eclipse.lsp4j.ExecuteCommandParams
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution
import org.opencds.cqf.cql.ls.server.utility.Futures
import java.util.concurrent.CompletableFuture

class DebugCommandContribution(
    private val compilationManager: CqlCompilationManager,
    private val contentService: ContentService,
    private val igContextManager: IgContextManager,
    private val libraryResolutionManager: LibraryResolutionManager,
) : CommandContribution {
    companion object {
        const val START_DEBUG_COMMAND = "org.opencds.cqf.cql.debug.startDebugSession"
    }

    private var debugSession: DebugSession? = null

    override fun getCommands(): Set<String> {
        return setOf(START_DEBUG_COMMAND)
    }

    fun stop() {
        debugSession?.stop()
    }

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        if (START_DEBUG_COMMAND == params.command) {
            val session = debugSession
            if (session == null || !session.isActive()) {
                val debugServer = CqlDebugServer(compilationManager, contentService, igContextManager, libraryResolutionManager)
                debugServer.enableStreaming()
                val newSession = DebugSession(debugServer)
                debugSession = newSession
                return newSession.start().thenApply { it as Any }
            } else {
                return Futures.failed(
                    IllegalStateException("Please wait for the current debug session to end before starting a new one."),
                )
            }
        } else {
            throw IllegalArgumentException(
                "DebugPlugin doesn't support command ${params.command}",
            )
        }
    }
}
