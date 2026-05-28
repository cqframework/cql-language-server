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
        log.debug("viewElm: received")
        val args = params.arguments

        // Defensive check: ensure we have at least the URI
        if (args == null || args.isEmpty()) {
            log.debug("viewElm: no arguments provided")
            return CompletableFuture.completedFuture(null)
        }

        val uriString =
            (args[0] as? JsonElement)?.asString
                ?: run {
                    log.debug("viewElm: args[0] is not a JsonElement string (type={})", args[0]?.javaClass?.simpleName)
                    return CompletableFuture.completedFuture(null)
                }

        val elmType = (args.getOrNull(1) as? JsonElement)?.asString ?: "xml"
        log.debug("viewElm: uri={} elmType={}", uriString, elmType)

        return try {
            val uri =
                Uris.parseOrNull(uriString)
                    ?: run {
                        log.debug("viewElm: could not parse URI '{}'", uriString)
                        return CompletableFuture.completedFuture(null)
                    }
            log.debug("viewElm: compiling {} as {}", uri, elmType)
            val compiler = cqlCompilationManager.compile(uri)
            if (compiler == null) {
                log.debug("viewElm: compile() returned null for {}", uri)
                return CompletableFuture.completedFuture(null)
            }
            val library = compiler.library
            if (library == null) {
                log.debug(
                    "viewElm: compiler.library is null for {}; exceptions ({}): {}",
                    uri,
                    compiler.exceptions?.size,
                    compiler.exceptions?.joinToString("; ") { "${it.severity}: ${it.message}" },
                )
                return CompletableFuture.completedFuture(null)
            }
            log.debug("viewElm: serializing ELM ({}) for library '{}'", elmType, library.identifier?.id)
            if (elmType.equals("xml", ignoreCase = true)) {
                CompletableFuture.completedFuture(convertToXml(library))
            } else {
                CompletableFuture.completedFuture(convertToJson(library))
            }
        } catch (e: Exception) {
            log.debug("viewElm: exception for '{}': {}", uriString, e.message, e)
            CompletableFuture.completedFuture(null)
        }
    }

    private fun convertToXml(library: Library): String = ElmXmlLibraryWriter().writeAsString(library)

    private fun convertToJson(library: Library): String = ElmJsonLibraryWriter().writeAsString(library)
}
