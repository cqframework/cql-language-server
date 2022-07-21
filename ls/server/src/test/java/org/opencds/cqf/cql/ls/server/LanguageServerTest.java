package org.opencds.cqf.cql.ls.server;

import static org.testng.Assert.assertNotNull;

import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.services.LanguageClient;
import org.mockito.Mockito;
import org.testng.annotations.Test;
// Just sketching out some tests here to get an idea of the scaffolding we need to make the language server easily testable
// We'll need to split out a few components to make it easier.
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
        // shutdown
        // exit

        InitializeResult initializeResult = server.initialize(new InitializeParams()).get();
        assertNotNull(initializeResult);

        server.initialized(new InitializedParams());

        server.shutdown().get(100, TimeUnit.MILLISECONDS);
        server.exit();
        server.exited().get(100, TimeUnit.MILLISECONDS);
    }

    // @Test
    public void hover() throws Exception {
        LanguageClient client = Mockito.mock(LanguageClient.class);
        CqlLanguageServer server = new CqlLanguageServer();
        server.connect(client);
        InitializeResult initializeResult = server.initialize(new InitializeParams()).get();
        assertNotNull(initializeResult);
        server.initialized(new InitializedParams());

        Hover hover = server.getTextDocumentService().hover(new HoverParams(new TextDocumentIdentifier("Test.cql"), new Position(2, 5))).get();


        server.shutdown().get(100, TimeUnit.MILLISECONDS);
        server.exit();
        server.exited().get(100, TimeUnit.MILLISECONDS);
    }
}
