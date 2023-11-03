package org.opencds.cqf.cql.ls.server.command;

import com.google.gson.JsonElement;
import org.cqframework.cql.cql2elm.CqlCompiler;
import org.cqframework.cql.cql2elm.CqlTranslator;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager;
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution;

import java.net.URI;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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
        String uriString = ((JsonElement) params.getArguments().get(0)).getAsString();
        try {

            URI uri = Uris.parseOrNull(uriString);
            CqlCompiler compiler = this.cqlCompilationManager.translate(uri);
            if (compiler != null) {
//                return CompletableFuture.completedFuture(compiler.toXml());
                return CompletableFuture.completedFuture(null);
            }

            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
