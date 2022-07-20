package org.opencds.cqf.cql.ls.plugin.debug.session;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.opencds.cqf.cql.debug.CqlDebugServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DebugSession {

    private static Logger log = LoggerFactory.getLogger(DebugSession.class);

    private static ExecutorService threadService = Executors.newCachedThreadPool();

    private CqlDebugServer debugServer;

    private Boolean isActive = false;

    private final CompletableFuture<Integer> port = new CompletableFuture<>();

    public DebugSession() {
        this.debugServer = new CqlDebugServer();
    }

    public CompletableFuture<Integer> start() {
            synchronized(this) {
                this.isActive = true;
            }
            startListening();
            return this.port;
    }

    public CqlDebugServer getDebugServer() {
        return this.debugServer;
    }

    public Boolean isActive() {
        return this.isActive;
    }

    private void startListening() {
        threadService.submit(() -> {
            try(ServerSocket serverSocket = new ServerSocket(0)) {
                serverSocket.setSoTimeout(10000);
                this.port.complete(serverSocket.getLocalPort());
                Socket s = serverSocket.accept();
                Launcher<IDebugProtocolClient> launcher = DSPLauncher.createServerLauncher(this.getDebugServer(),
                        s.getInputStream(), s.getOutputStream());
                this.getDebugServer().connect(launcher.getRemoteProxy());

                // We'll exit the server when the client disconnects.
                Future<Void> serverThread = launcher.startListening();
                this.getDebugServer().exited().get();
                serverThread.cancel(true);
            } catch (IOException e) {
                log.error("failed to launch debug server for debug session", e);
                this.port.completeExceptionally(e);
            } catch (CancellationException e) {
                log.debug("debug session cancelled", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("debug session interrupted");
            } catch (Exception e) {
                log.error("error in debug session", e);
            }
            synchronized(this) {
                this.isActive = false;
            }
        });
    }
}
