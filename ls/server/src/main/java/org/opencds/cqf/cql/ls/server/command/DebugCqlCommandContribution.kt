package org.opencds.cqf.cql.ls.server.command

import com.google.gson.JsonElement
import org.eclipse.lsp4j.ExecuteCommandParams
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.concurrent.CompletableFuture

// TODO: This will be moved to the debug plugin once that's more fully baked..
class DebugCqlCommandContribution(private val igContextManager: IgContextManager) : CommandContribution {

    companion object {
        // TODO: Delete once the plugin is fully supported
        const val START_DEBUG_COMMAND = "org.opencds.cqf.cql.ls.plugin.debug.startDebugSession"
    }

    override fun getCommands(): Set<String> = setOf(START_DEBUG_COMMAND)

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        return if (START_DEBUG_COMMAND == params.command) {
            executeCql(params)
        } else {
            super.executeCommand(params)
        }
    }

    private fun executeCql(params: ExecuteCommandParams): CompletableFuture<Any> {
        try {
            val arguments = params.arguments
                .map { it as JsonElement }
                .map { it.asString }

            // Temporarily redirect std out, because uh... I didn't do that very smart.
            val baosOut = ByteArrayOutputStream()
            System.setOut(PrintStream(baosOut))

            val baosErr = ByteArrayOutputStream()
            System.setErr(PrintStream(baosErr))

            try {
                val cli = CommandLine(CliCommand(igContextManager))
                cli.execute(*arguments.toTypedArray())
            } catch (e: Exception) {
                System.err.println("Exception occurred attempting to evaluate:")
                System.err.println(e.message)
            }

            var out = baosOut.toString()
            val err = baosErr.toString()

            if (err.isNotEmpty()) {
                out += "\nEvaluation logs:\n"
                out += err
            }

            return CompletableFuture.completedFuture(out)
        } finally {
            System.setOut(PrintStream(FileOutputStream(FileDescriptor.out)))
            System.setErr(PrintStream(FileOutputStream(FileDescriptor.err)))
        }
    }
}
