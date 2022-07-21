package org.opencds.cqf.cql.ls.server;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceFolder;
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

    @Test
    public void hoverInt() throws Exception {
        LanguageClient client = Mockito.mock(LanguageClient.class);
        ActiveContent content = new ActiveContent();
        List<WorkspaceFolder> folders = new ArrayList<>();
        CqlLanguageServer server = new CqlLanguageServer(new ServerContext(CompletableFuture.completedFuture(client), content, folders, new TestContentService()));
        server.connect(client);
        InitializeResult initializeResult = server.initialize(new InitializeParams()).get();
        assertNotNull(initializeResult);
        server.initialized(new InitializedParams());

        Hover hover = server.getTextDocumentService().hover(new HoverParams(new TextDocumentIdentifier("file:/org/opencds/cqf/cql/ls/server/Two.cql"), new Position(5, 2))).get();

        server.shutdown().get(100, TimeUnit.MILLISECONDS);
        server.exit();
        server.exited().get(100, TimeUnit.MILLISECONDS);

        assertNotNull(hover);
        assertNotNull(hover.getContents().getRight());

        MarkupContent markup = hover.getContents().getRight();
        assertEquals(markup.getKind(), "markdown");
        assertEquals(markup.getValue(), "```System.Integer```");
    }

    @Test
    public void hoverNothing() throws Exception {
        LanguageClient client = Mockito.mock(LanguageClient.class);
        ActiveContent content = new ActiveContent();
        List<WorkspaceFolder> folders = new ArrayList<>();
        CqlLanguageServer server = new CqlLanguageServer(new ServerContext(CompletableFuture.completedFuture(client), content, folders, new TestContentService()));
        server.connect(client);
        InitializeResult initializeResult = server.initialize(new InitializeParams()).get();
        assertNotNull(initializeResult);
        server.initialized(new InitializedParams());

        Hover hover = server.getTextDocumentService().hover(new HoverParams(new TextDocumentIdentifier("file:/org/opencds/cqf/cql/ls/server/Two.cql"), new Position(2, 0))).get();

        server.shutdown().get(100, TimeUnit.MILLISECONDS);
        server.exit();
        server.exited().get(100, TimeUnit.MILLISECONDS);

        assertNull(hover);
    }

    @Test
    public void hoverList() throws Exception {
        LanguageClient client = Mockito.mock(LanguageClient.class);
        ActiveContent content = new ActiveContent();
        List<WorkspaceFolder> folders = new ArrayList<>();
        CqlLanguageServer server = new CqlLanguageServer(new ServerContext(CompletableFuture.completedFuture(client), content, folders, new TestContentService()));
        server.connect(client);
        InitializeResult initializeResult = server.initialize(new InitializeParams()).get();
        assertNotNull(initializeResult);
        server.initialized(new InitializedParams());

        Hover hover = server.getTextDocumentService().hover(new HoverParams(new TextDocumentIdentifier("file:/org/opencds/cqf/cql/ls/server/Two.cql"), new Position(8, 2))).get();

        server.shutdown().get(100, TimeUnit.MILLISECONDS);
        server.exit();
        server.exited().get(100, TimeUnit.MILLISECONDS);

        assertNotNull(hover);
        assertNotNull(hover.getContents().getRight());

        MarkupContent markup = hover.getContents().getRight();
        assertEquals(markup.getKind(), "markdown");
        assertEquals(markup.getValue(), "```list<System.Integer>```");
    }
}
