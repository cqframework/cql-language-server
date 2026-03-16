package org.opencds.cqf.cql.ls.server.utility

import org.cqframework.cql.cql2elm.CqlCompilerException
import org.cqframework.cql.cql2elm.CqlSemanticException
import org.cqframework.cql.cql2elm.tracking.TrackBack
import org.eclipse.lsp4j.DiagnosticSeverity
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticsTest {
    // -----------------------------------------------------------------------
    // convert(CqlCompilerException)
    // -----------------------------------------------------------------------

    @Test
    fun convert_withLocator_appliesIndexConversion() {
        // TrackBack is 1-indexed; LSP is 0-indexed.
        // startChar -1, endChar no -1.
        val tb = locator(5, 3, 5, 15)
        val d = Diagnostics.convert(exception("err", tb, CqlCompilerException.ErrorSeverity.Error))

        assertNotNull(d)
        assertEquals(4, d!!.range.start.line)
        assertEquals(2, d.range.start.character)
        assertEquals(4, d.range.end.line)
        assertEquals(15, d.range.end.character) // end char: no -1
    }

    @Test
    fun convert_withNullLocator_returnsNull() {
        val error = CqlSemanticException("no locator", null, CqlCompilerException.ErrorSeverity.Error, null)
        assertNull(Diagnostics.convert(error))
    }

    @Test
    fun convert_line1Char1_clampedToZero() {
        // Line 1, char 1 → line 0, char 0 (max(..., 0) prevents negative)
        val tb = locator(1, 1, 1, 1)
        val d = Diagnostics.convert(exception("msg", tb, CqlCompilerException.ErrorSeverity.Error))

        assertNotNull(d)
        assertEquals(0, d!!.range.start.line)
        assertEquals(0, d.range.start.character)
        assertEquals(0, d.range.end.line)
        assertEquals(1, d.range.end.character) // endChar = 1 (no -1)
    }

    @Test
    fun convert_messagePropagated() {
        val tb = locator(2, 1, 2, 5)
        val d = Diagnostics.convert(exception("my error message", tb, CqlCompilerException.ErrorSeverity.Error))

        assertEquals("my error message", d!!.message)
    }

    // -----------------------------------------------------------------------
    // severity mapping
    // -----------------------------------------------------------------------

    @Test
    fun severity_error_mapsToError() {
        val tb = locator(1, 1, 1, 5)
        val d = Diagnostics.convert(exception("msg", tb, CqlCompilerException.ErrorSeverity.Error))
        assertEquals(DiagnosticSeverity.Error, d!!.severity)
    }

    @Test
    fun severity_warning_mapsToWarning() {
        val tb = locator(1, 1, 1, 5)
        val d = Diagnostics.convert(exception("msg", tb, CqlCompilerException.ErrorSeverity.Warning))
        assertEquals(DiagnosticSeverity.Warning, d!!.severity)
    }

    @Test
    fun severity_info_mapsToInformation() {
        val tb = locator(1, 1, 1, 5)
        val d = Diagnostics.convert(exception("msg", tb, CqlCompilerException.ErrorSeverity.Info))
        assertEquals(DiagnosticSeverity.Information, d!!.severity)
    }

    // -----------------------------------------------------------------------
    // convert(Iterable<CqlCompilerException>)
    // -----------------------------------------------------------------------

    @Test
    fun convertIterable_emptyList_returnsEmptySet() {
        val result = Diagnostics.convert(emptyList())
        assertNotNull(result)
        assertTrue(result.isEmpty())
    }

    @Test
    fun convertIterable_mixedLocators_onlyLocatedIncluded() {
        val located = exception("located", locator(1, 1, 1, 10), CqlCompilerException.ErrorSeverity.Error)
        val unlocated = CqlSemanticException("unlocated", null, CqlCompilerException.ErrorSeverity.Error, null)

        val result = Diagnostics.convert(listOf(located, unlocated))

        assertEquals(1, result.size)
    }

    @Test
    fun convertIterable_twoLocatedErrors_bothIncluded() {
        val e1 = exception("first", locator(1, 1, 1, 5), CqlCompilerException.ErrorSeverity.Error)
        val e2 = exception("second", locator(2, 1, 2, 5), CqlCompilerException.ErrorSeverity.Error)

        val result = Diagnostics.convert(listOf(e1, e2))

        assertEquals(2, result.size)
    }

    companion object {
        private fun locator(
            startLine: Int,
            startChar: Int,
            endLine: Int,
            endChar: Int,
        ) = TrackBack(VersionedIdentifier(), startLine, startChar, endLine, endChar)

        private fun exception(
            message: String,
            tb: TrackBack,
            severity: CqlCompilerException.ErrorSeverity,
        ) = CqlSemanticException(message, tb, severity, null)
    }
}
