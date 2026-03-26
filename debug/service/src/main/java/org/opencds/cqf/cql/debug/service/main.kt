package org.opencds.cqf.cql.debug.service

import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.opencds.cqf.cql.debug.CqlDebugServer
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler

private val log = LoggerFactory.getLogger("org.opencds.cqf.cql.debug.service.Main")

fun main(args: Array<String>) {
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()

    log.info("java.version is {}", System.getProperty("java.version"))
    log.info("cql-debug version is {}", CqlDebugServer::class.java.`package`.implementationVersion)

    val server = CqlDebugServer()

    @Suppress("java:S106")
    val launcher = DSPLauncher.createServerLauncher(server, System.`in`, System.out)

    server.connect(launcher.remoteProxy)
    val serverThread = launcher.startListening()

    server.exited().get()
    serverThread.cancel(true)
}
