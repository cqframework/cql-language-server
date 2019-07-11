package org.cqframework.cql.ls;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

public class Main {
    private static final Logger LOG = Logger.getLogger("main");
    public static void main(String[] args) {
        try {
            CqlLanguageServer server = new CqlLanguageServer();
            Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, System.in, System.out);

            server.installClient(launcher.getRemoteProxy());
            launcher.startListening();
            LOG.info(String.format("java.version is %s", System.getProperty("java.version")));
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, t.getMessage(), t);

            System.exit(1);
        }
    }
}
