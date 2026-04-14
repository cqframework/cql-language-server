package org.opencds.cqf.cql.ls.plugin.debug.session

import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.opencds.cqf.cql.debug.CqlDebugServer
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DebugSession {
    companion object {
        private val log = LoggerFactory.getLogger(DebugSession::class.java)
        private val threadService: ExecutorService = Executors.newCachedThreadPool()
    }

    private val debugServer: CqlDebugServer = CqlDebugServer()

    @Volatile
    private var isActiveFlag: Boolean = false

    private val port: CompletableFuture<Int> = CompletableFuture()

    fun start(): CompletableFuture<Int> {
        synchronized(this) {
            isActiveFlag = true
        }
        startListening()
        return this.port
    }

    fun getDebugServer(): CqlDebugServer {
        return this.debugServer
    }

    fun isActive(): Boolean {
        return this.isActiveFlag
    }

    private fun startListening() {
        threadService.submit {
            try {
                ServerSocket(0).use { serverSocket ->
                    serverSocket.soTimeout = 10000
                    this.port.complete(serverSocket.localPort)
                    val s = serverSocket.accept()
                    val launcher =
                        DSPLauncher.createServerLauncher(
                            this.getDebugServer(),
                            s.getInputStream(),
                            s.getOutputStream(),
                        )
                    this.getDebugServer().connect(launcher.remoteProxy)

                    // We'll exit the server when the client disconnects.
                    val serverThread = launcher.startListening()
                    this.getDebugServer().exited().get()
                    serverThread.cancel(true)
                }
            } catch (e: IOException) {
                log.error("failed to launch debug server for debug session", e)
                this.port.completeExceptionally(e)
            } catch (e: CancellationException) {
                log.debug("debug session cancelled", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                log.debug("debug session interrupted")
            } catch (e: Exception) {
                log.error("error in debug session", e)
            }
            synchronized(this) {
                isActiveFlag = false
            }
        }
    }
}
