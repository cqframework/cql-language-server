package org.opencds.cqf.cql.debug.service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.opencds.cqf.cql.debug.CqlDebugServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

/**
 * This class starts a CqlDebugServer listening on std-in and std-out. NOTE: This stand-alone server
 * is not the primary way the CqlDebugServer is expected to be used. Rather, the typically usage
 * will be as a plugin to the CqlLanguageServer to add debug capabilities
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    /**
     * Entrypoint for the cql-debug-service
     *
     * @param args the commandline parameters (none supported currently)
     * @throws InterruptedException if server thread is cancelled
     * @throws ExecutionException if server thread errors
     */
    public static void main(String[] args) throws InterruptedException, ExecutionException {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        log.info("java.version is {}", System.getProperty("java.version"));
        log.info("cql-debug version is {}", CqlDebugServer.class.getPackage().getImplementationVersion());

        CqlDebugServer server = new CqlDebugServer();

        @SuppressWarnings("java:S106")
        Launcher<IDebugProtocolClient> launcher = DSPLauncher.createServerLauncher(server, System.in, System.out);

        server.connect(launcher.getRemoteProxy());
        Future<Void> serverThread = launcher.startListening();

        server.exited().get();
        serverThread.cancel(true);
    }
}
