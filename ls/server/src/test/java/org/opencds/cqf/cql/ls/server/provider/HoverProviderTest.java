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
import org.opencds.cqf.cql.ls.server.config.TestConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;


@SpringBootTest(classes = {TestConfig.class})
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
        assertEquals("markdown", markup.getKind());
        assertEquals("```cql\nSystem.Integer\n```", markup.getValue());
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
        assertEquals("markdown", markup.getKind());
        assertEquals("```cql\nlist<System.Integer>\n```", markup.getValue());
    }

}
