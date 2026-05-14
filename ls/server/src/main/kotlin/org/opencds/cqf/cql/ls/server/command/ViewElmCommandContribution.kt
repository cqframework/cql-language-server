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
            log.warn("viewElm: no arguments provided")
            return CompletableFuture.completedFuture(null)
        }

        val uriString =
            (args[0] as? JsonElement)?.asString
                ?: run {
                    log.warn("viewElm: first argument is not a string")
                    return CompletableFuture.completedFuture(null)
                }

        val elmType = (args.getOrNull(1) as? JsonElement)?.asString ?: "xml"
        log.debug("viewElm: uri={} elmType={}", uriString, elmType)

        return try {
            val uri =
                Uris.parseOrNull(uriString)
                    ?: run {
                        log.warn("viewElm: could not parse uri={}", uriString)
                        return CompletableFuture.completedFuture(null)
                    }
            log.debug("viewElm: compiling")
            val compiler =
                cqlCompilationManager.compile(uri)
                    ?: run {
                        log.warn("viewElm: compilation returned null for uri={}", uriString)
                        return CompletableFuture.completedFuture(null)
                    }
            val library =
                compiler.library
                    ?: run {
                        log.warn("viewElm: library is null for uri={}", uriString)
                        return CompletableFuture.completedFuture(null)
                    }
            log.debug("viewElm: serializing as {}", elmType)
            val elm =
                if (elmType.equals("xml", ignoreCase = true)) {
                    convertToXml(library)
                } else {
                    convertToJson(library)
                }
            log.debug("viewElm: serialization complete, length={}", elm.length)
            CompletableFuture.completedFuture(elm)
        } catch (e: Exception) {
            log.error("viewElm: exception for uri={}", uriString, e)
            CompletableFuture.completedFuture(null)
        }
    }

    private fun convertToXml(library: Library): String = ElmXmlLibraryWriter().writeAsString(library)

    private fun convertToJson(library: Library): String = ElmJsonLibraryWriter().writeAsString(library)
}
