package org.opencds.cqf.cql.ls.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.cqframework.cql.cql2elm.CqlTranslator;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.opencds.cqf.cql.engine.execution.CqlEngine;
import org.opencds.cqf.cql.ls.server.CqlLanguageServer;
import org.opencds.cqf.cql.ls.server.config.ServerConfig;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Import;

/**
 * Main entry point for the CQL Language Server service.
 * <p>
 * This class starts a CqlLanguageServer running as a service that communicates
 * via the Language Server Protocol (LSP) over stdin/stdout. It is designed to be
 * launched by LSP clients such as VS Code extensions.
 * <p>
 * Key behaviors:
 * <ul>
 *   <li>Runs as a Spring Boot CommandLineRunner (non-web application)</li>
 *   <li>Logs to stderr to keep stdout clear for LSP communication</li>
 *   <li>Monitors both exit() notifications and connection closure for shutdown</li>
 *   <li>Terminates the JVM with System.exit(0) when the connection closes</li>
 * </ul>
 */
@Import(ServerConfig.class)
public class Main implements CommandLineRunner {

    private static final Logger log = (Logger) LoggerFactory.getLogger(Main.class);

    /**
     * Entrypoint for the cql-ls-service
     *
     * @param args the command-line parameters (none supported currently)
     */
    public static void main(String[] args) {
        configureLogging();
        SpringApplication.run(Main.class, args);
    }

    @Autowired
    CqlLanguageServer server;

    @Override
    public void run(String... args) {
        @SuppressWarnings("java:S106")
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, System.in, System.out);

        LanguageClient client = launcher.getRemoteProxy();

        // Logging Strategy:
        // Currently logs are written to stderr (configured in logback.xml).
        // The client can capture stderr and display it separately.
        //
        // Alternative: Send logs to the client's LSP window/logMessage API
        // by uncommenting the line below. This would show logs in VS Code's
        // Output panel for the language server channel.
        //
        // Trade-offs:
        // - stderr: Simpler, works with any LSP client, easier to redirect to files
        // - client API: Integrated into IDE, automatic log level filtering in UI
        //
        // setupClientAppender(client);

        server.connect(client);
        Future<Void> serverThread = launcher.startListening();

        log.info("java.version: {}", System.getProperty("java.version"));
        log.info(
                "cql-language-server version: {}",
                CqlLanguageServer.class.getPackage().getImplementationVersion());
        log.info("cql-translator version: {}", CqlTranslator.class.getPackage().getImplementationVersion());
        log.info("cql-engine version: {}", CqlEngine.class.getPackage().getImplementationVersion());

        log.info("cql-language-server started");

        // Shutdown Strategy:
        // Monitor two conditions in parallel to handle both normal and abnormal shutdowns:
        // 1. server.exited() - Completes when LSP exit() notification is received (normal shutdown)
        // 2. connectionClosed - Completes when stdin/stdout closes (client disconnect/crash)
        //
        // We wait for whichever happens first. This ensures the server terminates properly
        // when VS Code closes, even if the exit() notification never arrives.

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Void> connectionClosed = CompletableFuture.runAsync(
                () -> {
                    try {
                        // This blocks until the LSP connection closes (stdin/stdout closed)
                        serverThread.get();
                        log.info("LSP connection closed");
                    } catch (Exception e) {
                        log.debug("Server thread exception", e);
                    }
                },
                executor);

        // Wait for whichever completes first: exit() or connection closure
        try {
            CompletableFuture.anyOf(server.exited(), connectionClosed).get();
        } catch (Exception e) {
            log.error("Error waiting for shutdown", e);
        }

        log.info("Shutting down language server");
        serverThread.cancel(true);
        executor.shutdownNow();

        // Force JVM termination to ensure all threads (including Spring-managed ones) are stopped.
        // Using System.exit(0) is necessary because Spring Boot may have non-daemon threads running.
        System.exit(0);
    }

    /**
     * Configures SLF4J logging bridge to redirect java.util.logging to SLF4J.
     * This ensures all logging from third-party libraries flows through our
     * configured Logback appenders (currently configured to write to stderr).
     */
    public static void configureLogging() {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    /**
     * Alternative logging approach: Send logs to the LSP client via window/logMessage.
     * <p>
     * When enabled, this appender sends all log messages to the language client's
     * logMessage API, which displays them in the IDE's Output panel for the
     * language server channel.
     * <p>
     * Benefits:
     * <ul>
     *   <li>Logs appear directly in VS Code's Output panel</li>
     *   <li>IDE provides UI controls for filtering log levels</li>
     *   <li>No need to configure separate stderr capture</li>
     * </ul>
     * <p>
     * Drawbacks:
     * <ul>
     *   <li>Logs can't be easily redirected to files</li>
     *   <li>May clutter the client connection with high-volume logging</li>
     *   <li>Dependent on client implementation of window/logMessage</li>
     * </ul>
     * <p>
     * To enable: Uncomment the setupClientAppender(client) call on line 63.
     *
     * @param client The LSP language client to send log messages to
     */
    private static void setupClientAppender(LanguageClient client) {
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        LanguageClientAppender appender = new LanguageClientAppender(client);
        appender.setContext(lc);
        appender.start();

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);
    }
}
