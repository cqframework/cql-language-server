package org.opencds.cqf.cql.ls.service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.opencds.cqf.cql.ls.server.CqlLanguageServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class starts a CqlLanguageServer running as a service listening on std-in/std-out
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);


    /**
     * Entrypoint for the cql-ls-service
     * @param args the commandline parameters (none supported currently)
     * @throws InterruptedException
     * @throws ExecutionException
     */
    public static void main(String[] args) throws InterruptedException, ExecutionException {
        logger.info("java.version is {}", System.getProperty("java.version"));
        logger.info("cql-language-server version is {}", CqlLanguageServer.class.getPackage().getImplementationVersion());

        CqlLanguageServer server = new CqlLanguageServer();

        @SuppressWarnings("java:S106")
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, System.in, System.out);

        server.connect(launcher.getRemoteProxy());
        Future<Void> serverThread = launcher.startListening();

        server.exited().get();
        serverThread.cancel(true);
    }
}
