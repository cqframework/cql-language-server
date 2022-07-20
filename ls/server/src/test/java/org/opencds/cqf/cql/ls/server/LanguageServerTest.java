package org.opencds.cqf.cql.ls.server;

import static org.testng.Assert.assertNotNull;

import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.mockito.Mockito;
import org.testng.annotations.Test;

public class LanguageServerTest {

    @Test
    public void handshake() throws Exception {
        LanguageClient client = Mockito.mock(LanguageClient.class);
        CqlLanguageServer server = new CqlLanguageServer();
        server.connect(client);

        // https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#lifeCycleMessages

        // Sequence for initialization
        // initialize
        // initialized
        // ...
        // do language server stuff
        // ...
        // server terminated
        // server exited

        InitializeResult initializeResult = server.initialize(new InitializeParams()).get();
        assertNotNull(initializeResult);

        server.initialized(new InitializedParams());

        server.shutdown().get(100, TimeUnit.MILLISECONDS);
        server.exit();
        server.exited().get(100, TimeUnit.MILLISECONDS);
    }
}
