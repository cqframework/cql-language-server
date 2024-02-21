package org.opencds.cqf.cql.ls.server.utility;

import java.util.HashSet;
import java.util.Set;
import org.cqframework.cql.cql2elm.CqlCompilerException;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

public class Diagnostics {
    private Diagnostics() {}

    public static Diagnostic convert(CqlCompilerException error) {
        if (error.getLocator() != null) {
            Range range = position(error);
            Diagnostic diagnostic = new Diagnostic();
            DiagnosticSeverity severity = severity(error.getSeverity());

            diagnostic.setSeverity(severity);
            diagnostic.setRange(range);
            diagnostic.setMessage(error.getMessage());

            return diagnostic;
        } else {
            return null;
        }
    }

    public static Set<Diagnostic> convert(Iterable<CqlCompilerException> errors) {
        Set<Diagnostic> result = new HashSet<>();
        for (CqlCompilerException error : errors) {
            Diagnostic diagnostic = convert(error);
            if (diagnostic != null) {
                result.add(diagnostic);
            }
        }
        return result;
    }

    private static DiagnosticSeverity severity(CqlCompilerException.ErrorSeverity severity) {
        switch (severity) {
            case Error:
                return DiagnosticSeverity.Error;
            case Warning:
                return DiagnosticSeverity.Warning;
            case Info:
            default:
                return DiagnosticSeverity.Information;
        }
    }

    private static Range position(CqlCompilerException error) {
        // The Language server API assumes 0 based indices and an exclusive range
        return new Range(
                new Position(
                        Math.max(error.getLocator().getStartLine() - 1, 0),
                        Math.max(error.getLocator().getStartChar() - 1, 0)),
                new Position(
                        Math.max(error.getLocator().getEndLine() - 1, 0),
                        Math.max(error.getLocator().getEndChar(), 0)));
    }
}
