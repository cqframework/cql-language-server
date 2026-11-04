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

        /** Bounded wait after exited() completes, before closing the accepted socket,
         *  to let the in-flight disconnect() RPC response finish writing. See the
         *  comment at the call site in [startListening] for why this is needed. */
        private const val SOCKET_CLOSE_GRACE_MS = 250L
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
                    // `.use` (not a bare `val s = socket.accept()`) ensures the accepted
                    // per-connection socket is always closed once this block exits — by
                    // any path (normal completion, exception, or interrupt). Without this,
                    // the client's TCP connection is silently abandoned open (no FIN/RST)
                    // when the debug session ends, so a client request made after the
                    // session has already exited hangs forever with no error.
                    socket.accept().use { s ->
                        val launcher =
                            DSPLauncher.createServerLauncher(
                                this.debugServer,
                                s.getInputStream(),
                                s.getOutputStream(),
                            )
                        this.debugServer.connect(launcher.remoteProxy)

                        // Started but its Future is intentionally not cancelled below — see note.
                        launcher.startListening()
                        log.debug("startListening: waiting for exited() future [thread={}]", Thread.currentThread().name)
                        val t0 = System.nanoTime()
                        this.debugServer.exited().get()
                        log.debug("startListening: exited() future completed [+{}ms]", (System.nanoTime() - t0) / 1_000_000)
                        // Do NOT serverThread.cancel(true) here: interrupting a thread blocked in
                        // NIO channel I/O closes the channel out from under it (ClosedByInterruptException),
                        // which can race an in-flight response/notification write (e.g. the disconnect
                        // response, or the terminated/exited events sent from inside disconnect()) still
                        // draining on that same thread, causing "Socket closed" and dropping the message
                        // before the client receives it. Falling out of the `.use` block below closes `s`,
                        // which unblocks the listener thread's next read via a clean EOF/IOException instead.
                        //
                        // Even without cancel(true), `exited().get()` unblocking this thread races the
                        // disconnect() handler thread's own remaining work: exitServer() completes the
                        // `exited` future as its last statement, but the disconnect() RPC call's own
                        // response is only written by lsp4j AFTER the handler method returns — on that
                        // other thread, after this thread has already woken up. Give it a short bounded
                        // window to finish that write before closing the socket out from under it.
                        Thread.sleep(SOCKET_CLOSE_GRACE_MS)
                        log.debug("startListening: grace period elapsed [+{}ms]", (System.nanoTime() - t0) / 1_000_000)
                    }
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
