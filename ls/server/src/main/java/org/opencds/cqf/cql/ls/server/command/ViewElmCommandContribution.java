package org.opencds.cqf.cql.ls.server.command;

import com.google.gson.JsonElement;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.cqframework.cql.cql2elm.CqlCompiler;
import org.cqframework.cql.elm.serializing.ElmJsonLibraryWriter;
import org.cqframework.cql.elm.serializing.ElmXmlLibraryWriter;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.hl7.elm.r1.Library;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager;
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution;

public class ViewElmCommandContribution implements CommandContribution {
    private static final String VIEW_ELM_COMMAND = "org.opencds.cqf.cql.ls.viewElm";

    private final CqlCompilationManager cqlCompilationManager;

    public ViewElmCommandContribution(CqlCompilationManager cqlCompilationManager) {
        this.cqlCompilationManager = cqlCompilationManager;
    }

    @Override
    public Set<String> getCommands() {
        return Collections.singleton(VIEW_ELM_COMMAND);
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        switch (params.getCommand()) {
            case VIEW_ELM_COMMAND:
                return this.viewElm(params);
            default:
                return CommandContribution.super.executeCommand(params);
        }
    }

    // There's currently not a "show text file" or similar command in the LSP spec,
    // So it's not client agnostic. The client has to know that the result of this
    // command
    // is XML and display it accordingly.
    private CompletableFuture<Object> viewElm(ExecuteCommandParams params) {
        List<Object> args = params.getArguments();

        // Defensive check: ensure we have at least the URI
        if (args == null || args.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        String uriString = ((JsonElement) args.get(0)).getAsString();

        // Handle missing or null elmType by defaulting to "xml"
        String elmType = "xml";
        if (args.size() > 1 && args.get(1) != null) {
            elmType = ((JsonElement) args.get(1)).getAsString();
        }

        try {
            URI uri = Uris.parseOrNull(uriString);
            CqlCompiler compiler = this.cqlCompilationManager.compile(uri);

            if (compiler != null) {
                // Use .equalsIgnoreCase for better robustness
                if ("xml".equalsIgnoreCase(elmType)) {
                    return CompletableFuture.completedFuture(convertToXml(compiler.getLibrary()));
                } else {
                    return CompletableFuture.completedFuture(convertToJson(compiler.getLibrary()));
                }
            }

            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            // Log the error here if possible to avoid "silent" failures
            return CompletableFuture.completedFuture(null);
        }
    }

    private static String convertToXml(Library library) throws IOException {
        ElmXmlLibraryWriter writer = new ElmXmlLibraryWriter();
        return writer.writeAsString(library);
    }

    private static String convertToJson(Library library) throws IOException {
        ElmJsonLibraryWriter writer = new ElmJsonLibraryWriter();
        return writer.writeAsString(library);
    }
}
