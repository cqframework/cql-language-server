package org.opencds.cqf.cql.ls.server.provider;

import static com.google.common.base.Preconditions.checkNotNull;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import org.cqframework.cql.tools.formatter.CqlFormatterVisitor;
import org.cqframework.cql.tools.formatter.CqlFormatterVisitor.FormatResult;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FormattingProvider {

    private static Logger log = LoggerFactory.getLogger(FormattingProvider.class);

    private ContentService contentService;

    public FormattingProvider(ContentService contentService) {
        this.contentService = contentService;
    }

    public List<TextEdit> format(String uri) {
        URI u = checkNotNull(Uris.parseOrNull(uri));

        FormatResult fr;
        try {
            fr = CqlFormatterVisitor.getFormattedOutput(this.contentService.read(u));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (!fr.getErrors().isEmpty()) {
            log.error("cql format");
            throw new RuntimeException("Unable to format CQL");
        }

        TextEdit te = new TextEdit(
                new Range(new Position(0, 0), new Position(Integer.MAX_VALUE, Integer.MAX_VALUE)),
                fr.getOutput());

        return Collections.singletonList(te);
    }
}
