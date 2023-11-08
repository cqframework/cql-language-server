package org.opencds.cqf.cql.ls.service;

import org.cqframework.cql.cql2elm.CqlTranslator;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.opencds.cqf.cql.engine.execution.CqlEngine;
import org.opencds.cqf.cql.ls.server.CqlLanguageServer;
import org.opencds.cqf.cql.ls.server.config.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Import;

import java.util.concurrent.Future;

/**
 * This class starts a CqlLanguageServer running as a service listening on std-in/std-out
 */
@Import(ServerConfig.class)
public class Main implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    /**
     * Entrypoint for the cql-ls-service
     *
     * @param args the commandline parameters (none supported currently)
     */
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @Autowired
    CqlLanguageServer server;

    @Override
    public void run(String... args) throws Exception {
        configureLogging();

        log.info("java.version: {}", System.getProperty("java.version"));
        log.info("cql-language-server version: {}",
                CqlLanguageServer.class.getPackage().getImplementationVersion());
        log.info("cql-translator version: {}",
                CqlTranslator.class.getPackage().getImplementationVersion());
        log.info("cql-engine version: {}", CqlEngine.class.getPackage().getImplementationVersion());

        @SuppressWarnings("java:S106")
        Launcher<LanguageClient> launcher =
                LSPLauncher.createServerLauncher(server, System.in, System.out);

        LanguageClient client = launcher.getRemoteProxy();
        server.connect(client);
        Future<Void> serverThread = launcher.startListening();

        server.exited().get();
        serverThread.cancel(true);
    }

    public static void configureLogging() {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }
}
