package org.opencds.cqf.cql.ls.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.opencds.cqf.cql.ls.server.config.TestConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// Just sketching out some tests here to get an idea of the scaffolding we need to make the language
// server easily testable
// We'll need to split out a few components to make it easier.

@SpringBootTest(classes = {TestConfig.class})
class LanguageServerTest {

    @Autowired
    CqlLanguageServer server;

    LanguageClient client = Mockito.mock(LanguageClient.class);

    @Test
    void handshake() throws Exception {
        // https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#lifeCycleMessages

        // Sequence for initialization
        // initialize
        // initialized
        // ...
        // do language server stuff
        // ...
        // shutdown
        // exit

        assertNotNull(server);
    }

    @Test
    void hoverInt() throws Exception {
        Hover hover = server.getTextDocumentService()
                .hover(new HoverParams(
                        new TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/Two.cql"), new Position(5, 2)))
                .get();

        assertNotNull(hover);
        assertNotNull(hover.getContents().getRight());

        MarkupContent markup = hover.getContents().getRight();
        assertEquals("markdown", markup.getKind());
        assertEquals("```cql\nSystem.Integer\n```", markup.getValue());
    }

    @Test
    void hoverNothing() throws Exception {
        Hover hover = server.getTextDocumentService()
                .hover(new HoverParams(
                        new TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/Two.cql"), new Position(2, 0)))
                .get();

        assertNull(hover);
    }

    @Test
    void hoverList() throws Exception {
        Hover hover = server.getTextDocumentService()
                .hover(new HoverParams(
                        new TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/Two.cql"), new Position(8, 2)))
                .get();

        assertNotNull(hover);
        assertNotNull(hover.getContents().getRight());

        MarkupContent markup = hover.getContents().getRight();
        assertEquals("markdown", markup.getKind());
        assertEquals("```cql\nlist<System.Integer>\n```", markup.getValue());
    }
}
