package org.opencds.cqf.cql.ls.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.config.TestConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = {TestConfig.class})
public class DiagnosticsServiceTest {

    @Autowired
    DiagnosticsService diagnosticsService;

    @Test
    public void missingInclude() {
        URI uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/MissingInclude.cql");
        Map<URI, Set<Diagnostic>> diagnostics = diagnosticsService.lint(uri);

        assertTrue(diagnostics.containsKey(uri));

        Set<Diagnostic> dSet = diagnostics.get(uri);

        assertEquals(1, dSet.size());

        Diagnostic d = dSet.iterator().next();

        assertEquals(d.getRange(), new Range(new Position(2, 0), new Position(2, 15)));
    }
}
