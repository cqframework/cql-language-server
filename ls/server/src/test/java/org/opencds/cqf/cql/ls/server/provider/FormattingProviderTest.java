package org.opencds.cqf.cql.ls.server.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.server.service.TestContentService;

class FormattingProviderTest {

    private static FormattingProvider formattingProvider;

    @BeforeAll
    static void beforeAll() {
        ContentService cs = new TestContentService();
        formattingProvider = new FormattingProvider(cs);
    }

    @Test
    void format_validCql_returnsOneEdit() throws Exception {
        List<TextEdit> edits = formattingProvider.format("/org/opencds/cqf/cql/ls/server/Two.cql");
        assertEquals(1, edits.size());
        TextEdit edit = edits.get(0);
        assertEquals(new Position(0, 0), edit.getRange().getStart());
        assertNotNull(edit.getNewText());
        assertFalse(edit.getNewText().isBlank());
    }

    @Test
    void format_syntaxError_throwsIllegalArgument() {
        assertThrows(
                IllegalArgumentException.class,
                () -> formattingProvider.format("/org/opencds/cqf/cql/ls/server/SyntaxError.cql"));
    }
}
