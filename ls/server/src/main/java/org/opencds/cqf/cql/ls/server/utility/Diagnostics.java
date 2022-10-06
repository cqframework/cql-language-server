package org.opencds.cqf.cql.ls.server.utility;

import java.util.HashSet;
import java.util.Set;
import org.cqframework.cql.cql2elm.CqlTranslatorException;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Range;

public class Diagnostics {
    private Diagnostics() {}


    public static Diagnostic convert(CqlTranslatorException error) {
        if (error.getLocator() != null) {
            Range range = TrackBacks.toRange(error.getLocator());
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

    public static Set<Diagnostic> convert(Iterable<CqlTranslatorException> errors) {
        Set<Diagnostic> result = new HashSet<>();
        for (CqlTranslatorException error : errors) {
            Diagnostic diagnostic = convert(error);
            if (diagnostic != null) {
                result.add(diagnostic);
            }
        }
        return result;
    }

    private static DiagnosticSeverity severity(CqlTranslatorException.ErrorSeverity severity) {
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
}
