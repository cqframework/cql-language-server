package org.opencds.cqf.cql.ls;

import java.util.concurrent.Future;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger Log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            Log.info("java.version is {}", System.getProperty("java.version"));
            Log.info("cql-language-server version is {}", Main.class.getPackage().getImplementationVersion());
            
            CqlLanguageServer server = new CqlLanguageServer();
            Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, System.in, System.out);

            server.connect(launcher.getRemoteProxy());
            Future<Void> serverThread = launcher.startListening();

            server.exited().get();
            serverThread.cancel(true);
        } catch (Throwable t) {
            t.printStackTrace();
            Log.error("fatal error: {}", t.getMessage());

            System.exit(1);
        }
    }
}
