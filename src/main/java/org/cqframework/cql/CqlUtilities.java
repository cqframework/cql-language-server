package org.cqframework.cql;

import org.cqframework.cql.cql2elm.CqlTranslatorException;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Created by Bryn on 9/4/2018.
 */
public class CqlUtilities {

    static Diagnostic convert(CqlTranslatorException error) {
        if (error.getLocator() != null) {
            Range range = position(error);
            Diagnostic diagnostic = new Diagnostic();
            DiagnosticSeverity severity = severity(error.getSeverity());

            diagnostic.setSeverity(severity);
            diagnostic.setRange(range);
            diagnostic.setMessage(error.getMessage());

            return diagnostic;
        }
        else {
            LOG.warning("Skipped " + error.getMessage());

            return null;
        }
    }

    static List<Diagnostic> convert(Iterable<CqlTranslatorException> errors) {
        ArrayList result = new ArrayList<>();
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

    private static Range position(CqlTranslatorException error) {
        // The Language server API assumes 0 based indices and an exclusive range
        return new Range(
                new Position(
                        error.getLocator().getStartLine() - 1,
                        error.getLocator().getStartChar() - 1
                ),
                new Position(
                        error.getLocator().getEndLine() - 1,
                        error.getLocator().getEndChar()
                )
        );
    }

    private static final Logger LOG = Logger.getLogger("main");
}
