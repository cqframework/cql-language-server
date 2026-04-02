package org.opencds.cqf.cql.ls.server.utility

import org.cqframework.cql.cql2elm.CqlCompilerException
import org.cqframework.cql.cql2elm.tracking.TrackBack
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

object Diagnostics {
    @JvmStatic
    fun convert(error: CqlCompilerException): Diagnostic? {
        val locator = error.locator ?: return null
        val range = position(locator)
        val diagnostic = Diagnostic()
        diagnostic.severity = severity(error.severity)
        diagnostic.range = range
        diagnostic.message = error.message
        return diagnostic
    }

    @JvmStatic
    fun convert(errors: Iterable<CqlCompilerException>): Set<Diagnostic> = errors.mapNotNull { convert(it) }.toSet()

    private fun severity(severity: CqlCompilerException.ErrorSeverity): DiagnosticSeverity {
        return when (severity) {
            CqlCompilerException.ErrorSeverity.Error -> DiagnosticSeverity.Error
            CqlCompilerException.ErrorSeverity.Warning -> DiagnosticSeverity.Warning
            else -> DiagnosticSeverity.Information
        }
    }

    private fun position(locator: TrackBack): Range {
        return Range(
            Position(
                maxOf(locator.startLine - 1, 0),
                maxOf(locator.startChar - 1, 0),
            ),
            Position(
                maxOf(locator.endLine - 1, 0),
                maxOf(locator.endChar, 0),
            ),
        )
    }
}
