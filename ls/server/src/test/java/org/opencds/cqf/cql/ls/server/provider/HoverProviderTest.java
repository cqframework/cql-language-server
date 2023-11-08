package org.opencds.cqf.cql.ls.server.provider;

import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager;
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager;
import org.opencds.cqf.cql.ls.server.manager.IgContextManager;
import org.opencds.cqf.cql.ls.server.service.TestContentService;

import static org.junit.jupiter.api.Assertions.*;



public class HoverProviderTest {

    private static HoverProvider hoverProvider;

    @BeforeAll
    public static void beforeAll() {
        ContentService cs = new TestContentService();
        CqlCompilationManager cqlCompilationManager =
                new CqlCompilationManager(cs, new CompilerOptionsManager(cs), new IgContextManager(cs));
        hoverProvider = new HoverProvider(cqlCompilationManager);
    }

    @Test
    public void hoverInt() throws Exception {
        Hover hover = hoverProvider.hover(new HoverParams(
                new TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/Two.cql"),
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
                new TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/Two.cql"),
                new Position(2, 0)));

        assertNull(hover);
    }

    @Test
    public void hoverList() throws Exception {
        Hover hover = hoverProvider.hover(new HoverParams(
                new TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/Two.cql"),
                new Position(8, 2)));

        assertNotNull(hover);
        assertNotNull(hover.getContents().getRight());

        MarkupContent markup = hover.getContents().getRight();
        assertEquals("markdown", markup.getKind());
        assertEquals("```cql\nlist<System.Integer>\n```", markup.getValue());
    }

}
