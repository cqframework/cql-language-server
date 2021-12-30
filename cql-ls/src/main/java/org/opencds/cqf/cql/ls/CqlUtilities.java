package org.opencds.cqf.cql.ls;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.cqframework.cql.cql2elm.CqlTranslator;
import org.cqframework.cql.cql2elm.CqlTranslatorException;
import org.cqframework.cql.cql2elm.CqlTranslatorOptions;
import org.cqframework.cql.cql2elm.CqlTranslatorOptionsMapper;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by Bryn on 9/4/2018.
 */
public class CqlUtilities {

    private final static Logger Log = LoggerFactory.getLogger(CqlUtilities.class);

    public static URI getFhirBaseUri(URI uri) {
        // TODO: This should actually just recognize the FHIR types.
        int index = uri.getPath().lastIndexOf("Library/");
        if (index > -1) {
            uri = getHead(uri);
        }

        index = uri.getPath().lastIndexOf("/Library");
        if (index > -1) {
            uri = getHead(uri);
        }

        return uri;
    }

    public static URI getHead(URI uri) {
        String path = uri.getPath();
        if (path != null) {
            int index = path.lastIndexOf("/");
            if (index > -1) {
                return CqlUtilities.withPath(uri, path.substring(0, index));
            }

            return uri;
        }

        return uri;
    }

    public static CqlTranslatorOptions getTranslatorOptions(URI uri) {
        CqlTranslatorOptions options = null;
        URI baseUri = getHead(uri);
        if (!baseUri.getScheme().startsWith("http")) {

            Path path;
            try {
                path = Paths.get(baseUri);
            }
            catch (Exception e) {
                return null;
            }

            Path optionsPath = path.resolve("cql-options.json");
            File file = optionsPath.toFile();
            if (file.exists()) {
                options = CqlTranslatorOptionsMapper.fromFile(file.getAbsolutePath());
                Log.info(String.format("cql-options loaded from: %s", file.getAbsolutePath()));
            }
        }

        if (options == null) {
            options = CqlTranslatorOptions.defaultOptions();
            if (!options.getFormats().contains(CqlTranslator.Format.XML)) {
                options.getFormats().add(CqlTranslator.Format.XML);
            }
            Log.info("cql-options not found. Using default options.");
        }

        return options;
    }

    private static URI withPath(URI uri, String path) {
        try {
            return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), path, uri.getFragment(), uri.getQuery());
        }
        catch (Exception e) {
            return null;
        }
    }

    public static Diagnostic convert(CqlTranslatorException error) {
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
            Log.debug("Skipped " + error.getMessage());

            return null;
        }
    }

    public static List<Diagnostic> convert(Iterable<CqlTranslatorException> errors) {
        ArrayList<Diagnostic> result = new ArrayList<>();
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
}
