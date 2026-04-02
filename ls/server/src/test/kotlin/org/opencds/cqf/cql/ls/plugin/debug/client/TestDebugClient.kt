package org.opencds.cqf.cql.ls.plugin.debug.client

import org.eclipse.lsp4j.debug.ExitedEventArguments
import org.eclipse.lsp4j.debug.OutputEventArguments
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import java.util.concurrent.CompletableFuture

class TestDebugClient : IDebugProtocolClient {
    private var serverOutput: String? = null

    private val exitedFuture: CompletableFuture<Void> = CompletableFuture()

    fun getServerOutput(): String? {
        return this.serverOutput
    }

    override fun initialized() {}

    override fun output(args: OutputEventArguments) {
        // this.serverOutput = args.output
    }

    override fun exited(args: ExitedEventArguments) {
        this.serverOutput = "got exited"
        this.exitedFuture.complete(null)
    }

    fun exited(): CompletableFuture<Void> {
        return this.exitedFuture
    }
}
