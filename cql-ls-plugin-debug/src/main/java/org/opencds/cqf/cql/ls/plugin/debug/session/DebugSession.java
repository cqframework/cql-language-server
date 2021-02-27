package org.opencds.cqf.cql.ls.plugin.debug.session;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.opencds.cqf.cql.ls.plugin.debug.server.DebugServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DebugSession {

    private static Logger logger = LoggerFactory.getLogger(DebugSession.class);

    private static ExecutorService threadService = Executors.newCachedThreadPool();

    private DebugServer debugServer;

    private final CompletableFuture<Integer> port = new CompletableFuture<>();

    public DebugSession() {
        this.debugServer = new DebugServer();
    }

    public DebugSession(DebugServer debugServer) {
        this.debugServer = debugServer;
    }

    public CompletableFuture<Integer> start() {
            startListening();
            return this.port;
    }

    public DebugServer getDebugServer() {
        return this.debugServer;
    }

    private void startListening() {
        threadService.submit(() -> {
            try(ServerSocket serverSocket = new ServerSocket(0)) {
                // Wait for 10 seconds for a client to connect before closing;
                serverSocket.setSoTimeout(10000);
                this.port.complete(serverSocket.getLocalPort());
                Socket s = serverSocket.accept();
                Launcher<IDebugProtocolClient> launcher = DSPLauncher.createServerLauncher(this.getDebugServer(),
                        s.getInputStream(), s.getOutputStream());               
                this.getDebugServer().connect(launcher.getRemoteProxy());

                // We'll exit the server when the client disconnects.
                launcher.startListening().get();
            } catch (IOException e) {
                logger.error("failed to launch debug server for debug session", e);
                this.port.completeExceptionally(e);
            } catch (CancellationException e) {
                logger.debug("debug session cancelled", e);
                if (!this.port.isDone()) {
                    this.port.completeExceptionally(e);
                }
            } catch (Exception e) {
                logger.error("error in debug session", e);
                if (!this.port.isDone()) {
                    this.port.completeExceptionally(e);
                }
            }
        });
    }
}
