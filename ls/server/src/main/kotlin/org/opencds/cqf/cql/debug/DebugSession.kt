package org.opencds.cqf.cql.debug

import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DebugSession(
    private val debugServer: CqlDebugServer,
) {
    private val threadService: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable).apply { isDaemon = true }
        }

    companion object {
        private val log = LoggerFactory.getLogger(DebugSession::class.java)
    }

    @Volatile
    private var isActiveFlag: Boolean = false

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var stopped: Boolean = false

    private val port: CompletableFuture<Int> = CompletableFuture()

    fun start(): CompletableFuture<Int> {
        synchronized(this) {
            isActiveFlag = true
        }
        startListening()
        return this.port
    }

    fun isActive(): Boolean {
        return this.isActiveFlag
    }

    fun stop() {
        stopped = true
        threadService.shutdownNow()
        try {
            serverSocket?.close()
        } catch (_: IOException) {
            // already closed or never opened — nothing to do
        }
    }

    private fun startListening() {
        threadService.submit {
            try {
                ServerSocket(0).use { socket ->
                    this.serverSocket = socket
                    socket.soTimeout = 10000
                    this.port.complete(socket.localPort)
                    val s = socket.accept()
                    val launcher =
                        DSPLauncher.createServerLauncher(
                            this.debugServer,
                            s.getInputStream(),
                            s.getOutputStream(),
                        )
                    this.debugServer.connect(launcher.remoteProxy)

                    val serverThread = launcher.startListening()
                    log.debug("startListening: waiting for exited() future [thread={}]", Thread.currentThread().name)
                    val t0 = System.nanoTime()
                    this.debugServer.exited().get()
                    log.debug("startListening: exited() future completed [+{}ms]", (System.nanoTime() - t0) / 1_000_000)
                    serverThread.cancel(true)
                    log.debug("startListening: serverThread cancelled [+{}ms]", (System.nanoTime() - t0) / 1_000_000)
                }
            } catch (e: SocketTimeoutException) {
                log.debug("debug session accept timed out (no client connected within {}ms)", 10000)
                this.port.completeExceptionally(e)
            } catch (e: IOException) {
                if (stopped) {
                    log.debug("debug session stopped before client connected")
                } else {
                    log.error("failed to launch debug server for debug session", e)
                    this.port.completeExceptionally(e)
                }
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
