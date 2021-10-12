package org.opencds.cqf.cql.debug;

import java.util.concurrent.Future;

import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.opencds.cqf.cql.debug.server.DebugServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            logger.info("java.version is {}", System.getProperty("java.version"));
            logger.info("cql-debug-server-version is {}", Main.class.getPackage().getImplementationVersion());
            
            DebugServer server = new DebugServer();
            Launcher<IDebugProtocolClient> launcher = DSPLauncher.createServerLauncher(server, System.in, System.out);

            server.connect(launcher.getRemoteProxy());
            Future<Void> serverThread = launcher.startListening();

            server.exited().get();
            serverThread.cancel(true);
        } catch (Throwable t) {
            t.printStackTrace();
            logger.error("fatal error: {}", t.getMessage());

            System.exit(1);
        }
    }
}
