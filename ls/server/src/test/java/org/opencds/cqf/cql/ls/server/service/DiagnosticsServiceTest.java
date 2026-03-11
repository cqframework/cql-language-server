package org.opencds.cqf.cql.ls.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager;
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager;
import org.opencds.cqf.cql.ls.server.manager.IgContextManager;

class DiagnosticsServiceTest {

    private static DiagnosticsService diagnosticsService;

    @BeforeAll
    static void beforeAll() {
        ContentService cs = new TestContentService();
        CqlCompilationManager cqlCompilationManager =
                new CqlCompilationManager(cs, new CompilerOptionsManager(cs), new IgContextManager(cs));
        diagnosticsService = new DiagnosticsService(
                CompletableFuture.completedFuture(Mockito.mock(LanguageClient.class)), cqlCompilationManager, cs);
    }

    @Test
    void missingInclude() {
        URI uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/MissingInclude.cql");
        Map<URI, Set<Diagnostic>> diagnostics = diagnosticsService.lint(uri);

        assertTrue(diagnostics.containsKey(uri));

        Set<Diagnostic> dSet = diagnostics.get(uri);

        assertEquals(1, dSet.size());

        Diagnostic d = dSet.iterator().next();

        assertEquals(d.getRange(), new Range(new Position(2, 0), new Position(2, 15)));
    }

    @Test
    void validCql_noErrors() {
        URI uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/Two.cql");
        Map<URI, Set<Diagnostic>> diagnostics = diagnosticsService.lint(uri);

        assertTrue(diagnostics.containsKey(uri));
        assertTrue(diagnostics.get(uri).isEmpty());
    }

    @Test
    void syntaxError_returnsDiagnostic() {
        URI uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/SyntaxError.cql");
        Map<URI, Set<Diagnostic>> diagnostics = diagnosticsService.lint(uri);

        assertTrue(diagnostics.containsKey(uri));
        assertFalse(diagnostics.get(uri).isEmpty());

        Diagnostic d = diagnostics.get(uri).iterator().next();
        assertEquals(DiagnosticSeverity.Error, d.getSeverity());
    }
}
