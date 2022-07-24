package org.opencds.cqf.cql.ls.server.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;
import org.opencds.cqf.cql.ls.server.config.ServerConfig;
import org.opencds.cqf.cql.ls.server.config.TestConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = {ServerConfig.class, TestConfig.class},
        properties = {"spring.main.allow-bean-definition-overriding=true"})
public class HoverProviderTest {

    @Autowired
    HoverProvider hoverProvider;

    @Test
    public void hoverInt() throws Exception {
        Hover hover = hoverProvider.hover(new HoverParams(
                new TextDocumentIdentifier("file:/org/opencds/cqf/cql/ls/server/Two.cql"),
                new Position(5, 2)));

        assertNotNull(hover);
        assertNotNull(hover.getContents().getRight());

        MarkupContent markup = hover.getContents().getRight();
        assertEquals(markup.getKind(), "markdown");
        assertEquals(markup.getValue(), "```System.Integer```");
    }

    @Test
    public void hoverNothing() throws Exception {
        Hover hover = hoverProvider.hover(new HoverParams(
                new TextDocumentIdentifier("file:/org/opencds/cqf/cql/ls/server/Two.cql"),
                new Position(2, 0)));

        assertNull(hover);
    }

    @Test
    public void hoverList() throws Exception {
        Hover hover = hoverProvider.hover(new HoverParams(
                new TextDocumentIdentifier("file:/org/opencds/cqf/cql/ls/server/Two.cql"),
                new Position(8, 2)));

        assertNotNull(hover);
        assertNotNull(hover.getContents().getRight());

        MarkupContent markup = hover.getContents().getRight();
        assertEquals(markup.getKind(), "markdown");
        assertEquals(markup.getValue(), "```list<System.Integer>```");
    }

}
