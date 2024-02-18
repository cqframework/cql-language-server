package org.opencds.cqf.cql.ls.server.provider;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.cqframework.cql.tools.formatter.CqlFormatterVisitor;
import org.cqframework.cql.tools.formatter.CqlFormatterVisitor.FormatResult;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.core.utility.Uris;

public class FormattingProvider {
    private ContentService contentService;

    public FormattingProvider(ContentService contentService) {
        this.contentService = contentService;
    }

    public List<TextEdit> format(String uri) {
        URI u = Objects.requireNonNull(Uris.parseOrNull(uri));

        FormatResult fr = null;
        try {
            fr = CqlFormatterVisitor.getFormattedOutput(this.contentService.read(u));
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to format CQL due to an error.", e);
        }

        if (!fr.getErrors().isEmpty()) {
            throw new IllegalArgumentException(
                    String.join("\n", "Unable to format CQL due to syntax errors.",
                            "Please fix the errors and try again."));
        }

        TextEdit te = new TextEdit(
                new Range(new Position(0, 0), new Position(Integer.MAX_VALUE, Integer.MAX_VALUE)),
                fr.getOutput());

        return Collections.singletonList(te);
    }
}
