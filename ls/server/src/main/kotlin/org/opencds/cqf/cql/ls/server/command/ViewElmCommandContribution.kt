package org.opencds.cqf.cql.ls.server.command

import com.google.gson.JsonElement
import org.cqframework.cql.elm.serializing.ElmJsonLibraryWriter
import org.cqframework.cql.elm.serializing.ElmXmlLibraryWriter
import org.eclipse.lsp4j.ExecuteCommandParams
import org.hl7.elm.r1.Library
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

class ViewElmCommandContribution(private val cqlCompilationManager: CqlCompilationManager) : CommandContribution {
    companion object {
        private val log = LoggerFactory.getLogger(ViewElmCommandContribution::class.java)
        private const val VIEW_ELM_COMMAND = "org.opencds.cqf.cql.ls.viewElm"
    }

    override fun getCommands(): Set<String> = setOf(VIEW_ELM_COMMAND)

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        return when (params.command) {
            VIEW_ELM_COMMAND -> viewElm(params)
            else -> super.executeCommand(params)
        }
    }

    // There's currently not a "show text file" or similar command in the LSP spec,
    // So it's not client agnostic. The client has to know that the result of this
    // command is XML and display it accordingly.
    private fun viewElm(params: ExecuteCommandParams): CompletableFuture<Any> {
        val args = params.arguments

        // Defensive check: ensure we have at least the URI
        if (args == null || args.isEmpty()) {
            return CompletableFuture.completedFuture(null)
        }

        val uriString =
            (args[0] as? JsonElement)?.asString
                ?: return CompletableFuture.completedFuture(null)

        val elmType = (args.getOrNull(1) as? JsonElement)?.asString ?: "xml"

        return try {
            val uri =
                Uris.parseOrNull(uriString)
                    ?: return CompletableFuture.completedFuture(null)
            val compiler =
                cqlCompilationManager.compile(uri)
                    ?: return CompletableFuture.completedFuture(null)
            val library =
                compiler.library
                    ?: return CompletableFuture.completedFuture(null)
            if (elmType.equals("xml", ignoreCase = true)) {
                CompletableFuture.completedFuture(convertToXml(library))
            } else {
                CompletableFuture.completedFuture(convertToJson(library))
            }
        } catch (e: Exception) {
            CompletableFuture.completedFuture(null)
        }
    }

    private fun convertToXml(library: Library): String = ElmXmlLibraryWriter().writeAsString(library)

    private fun convertToJson(library: Library): String = ElmJsonLibraryWriter().writeAsString(library)
}
