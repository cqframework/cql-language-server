package org.opencds.cqf.cql.ls.service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.cqframework.cql.cql2elm.CqlTranslator;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.opencds.cqf.cql.engine.execution.CqlEngine;
import org.opencds.cqf.cql.evaluator.CqlEvaluator;
import org.opencds.cqf.cql.ls.server.CqlLanguageServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class starts a CqlLanguageServer running as a service listening on std-in/std-out
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    /**
     * Entrypoint for the cql-ls-service
     * @param args the commandline parameters (none supported currently)
     * @throws InterruptedException if server thread is cancelled
     * @throws ExecutionException  if server thread errors
     */
    public static void main(String[] args) throws InterruptedException, ExecutionException {
        log.info("java.version: {}", System.getProperty("java.version"));
        log.info("cql-language-server version: {}", CqlLanguageServer.class.getPackage().getImplementationVersion());
        log.info("cql-evaluator version: {}", CqlEvaluator.class.getPackage().getImplementationVersion());
        log.info("cql-translator version: {}", CqlTranslator.class.getPackage().getImplementationVersion());
        log.info("cql-engine version: {}", CqlEngine.class.getPackage().getImplementationVersion());

        CqlLanguageServer server = new CqlLanguageServer();

        @SuppressWarnings("java:S106")
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, System.in, System.out);

        LanguageClient client = launcher.getRemoteProxy();
        server.connect(client);
        Future<Void> serverThread = launcher.startListening();

        server.exited().get();
        serverThread.cancel(true);
    }
}
