package org.opencds.cqf.cql.ls.server.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import org.cqframework.cql.cql2elm.CqlCompilerException;
import org.cqframework.cql.cql2elm.CqlSemanticException;
import org.cqframework.cql.cql2elm.tracking.TrackBack;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.hl7.elm.r1.VersionedIdentifier;
import org.junit.jupiter.api.Test;

class DiagnosticsTest {

    private static TrackBack locator(int startLine, int startChar, int endLine, int endChar) {
        return new TrackBack(new VersionedIdentifier(), startLine, startChar, endLine, endChar);
    }

    private static CqlSemanticException exception(
            String message, TrackBack tb, CqlCompilerException.ErrorSeverity severity) {
        return new CqlSemanticException(message, tb, severity, null);
    }

    // -----------------------------------------------------------------------
    // convert(CqlCompilerException)
    // -----------------------------------------------------------------------

    @Test
    void convert_withLocator_appliesIndexConversion() {
        // TrackBack is 1-indexed; LSP is 0-indexed.
        // startChar -1, endChar no -1.
        TrackBack tb = locator(5, 3, 5, 15);
        Diagnostic d = Diagnostics.convert(exception("err", tb, CqlCompilerException.ErrorSeverity.Error));

        assertNotNull(d);
        assertEquals(4, d.getRange().getStart().getLine());
        assertEquals(2, d.getRange().getStart().getCharacter());
        assertEquals(4, d.getRange().getEnd().getLine());
        assertEquals(15, d.getRange().getEnd().getCharacter()); // end char: no -1
    }

    @Test
    void convert_withNullLocator_returnsNull() {
        CqlSemanticException error =
                new CqlSemanticException("no locator", null, CqlCompilerException.ErrorSeverity.Error, null);
        assertNull(Diagnostics.convert(error));
    }

    @Test
    void convert_line1Char1_clampedToZero() {
        // Line 1, char 1 → line 0, char 0 (max(..., 0) prevents negative)
        TrackBack tb = locator(1, 1, 1, 1);
        Diagnostic d = Diagnostics.convert(exception("msg", tb, CqlCompilerException.ErrorSeverity.Error));

        assertNotNull(d);
        assertEquals(0, d.getRange().getStart().getLine());
        assertEquals(0, d.getRange().getStart().getCharacter());
        assertEquals(0, d.getRange().getEnd().getLine());
        assertEquals(1, d.getRange().getEnd().getCharacter()); // endChar = 1 (no -1)
    }

    @Test
    void convert_messagePropagated() {
        TrackBack tb = locator(2, 1, 2, 5);
        Diagnostic d = Diagnostics.convert(exception("my error message", tb, CqlCompilerException.ErrorSeverity.Error));

        assertEquals("my error message", d.getMessage());
    }

    // -----------------------------------------------------------------------
    // severity mapping
    // -----------------------------------------------------------------------

    @Test
    void severity_error_mapsToError() {
        TrackBack tb = locator(1, 1, 1, 5);
        Diagnostic d = Diagnostics.convert(exception("msg", tb, CqlCompilerException.ErrorSeverity.Error));
        assertEquals(DiagnosticSeverity.Error, d.getSeverity());
    }

    @Test
    void severity_warning_mapsToWarning() {
        TrackBack tb = locator(1, 1, 1, 5);
        Diagnostic d = Diagnostics.convert(exception("msg", tb, CqlCompilerException.ErrorSeverity.Warning));
        assertEquals(DiagnosticSeverity.Warning, d.getSeverity());
    }

    @Test
    void severity_info_mapsToInformation() {
        TrackBack tb = locator(1, 1, 1, 5);
        Diagnostic d = Diagnostics.convert(exception("msg", tb, CqlCompilerException.ErrorSeverity.Info));
        assertEquals(DiagnosticSeverity.Information, d.getSeverity());
    }

    // -----------------------------------------------------------------------
    // convert(Iterable<CqlCompilerException>)
    // -----------------------------------------------------------------------

    @Test
    void convertIterable_emptyList_returnsEmptySet() {
        Set<Diagnostic> result = Diagnostics.convert(Collections.emptyList());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void convertIterable_mixedLocators_onlyLocatedIncluded() {
        CqlSemanticException located =
                exception("located", locator(1, 1, 1, 10), CqlCompilerException.ErrorSeverity.Error);
        CqlSemanticException unlocated =
                new CqlSemanticException("unlocated", null, CqlCompilerException.ErrorSeverity.Error, null);

        Set<Diagnostic> result = Diagnostics.convert(Arrays.asList(located, unlocated));

        assertEquals(1, result.size());
    }

    @Test
    void convertIterable_twoLocatedErrors_bothIncluded() {
        CqlSemanticException e1 = exception("first", locator(1, 1, 1, 5), CqlCompilerException.ErrorSeverity.Error);
        CqlSemanticException e2 = exception("second", locator(2, 1, 2, 5), CqlCompilerException.ErrorSeverity.Error);

        Set<Diagnostic> result = Diagnostics.convert(Arrays.asList(e1, e2));

        assertEquals(2, result.size());
    }
}
