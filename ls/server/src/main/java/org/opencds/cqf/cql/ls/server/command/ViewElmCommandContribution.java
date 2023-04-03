package org.opencds.cqf.cql.ls.server.command;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.cqframework.cql.cql2elm.CqlTranslator;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.manager.CqlTranslationManager;
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution;
import com.google.gson.JsonElement;

public class ViewElmCommandContribution implements CommandContribution {
    private static final String VIEW_ELM_COMMAND = "org.opencds.cqf.cql.ls.viewElm";
    private static final String VIEW_ELM_JSON_COMMAND = "org.opencds.cqf.cql.ls.viewElmJson";

    private final CqlTranslationManager cqlTranslationManager;

    public ViewElmCommandContribution(CqlTranslationManager cqlTranslationManager) {
        this.cqlTranslationManager = cqlTranslationManager;
    }

    @Override
    public Set<String> getCommands() {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(VIEW_ELM_COMMAND, VIEW_ELM_JSON_COMMAND)));
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        switch (params.getCommand()) {
            case VIEW_ELM_COMMAND:
                return this.viewElm(params, true);
            case VIEW_ELM_JSON_COMMAND:
                return this.viewElm(params, false);
            default:
                return CommandContribution.super.executeCommand(params);
        }
    }

    // There's currently not a "show text file" or similar command in the LSP spec,
    // So it's not client agnostic. The client has to know that the result of this
    // command is XML or JSON and display it accordingly.
    private CompletableFuture<Object> viewElm(ExecuteCommandParams params, Boolean isXml) {
        String uriString = ((JsonElement) params.getArguments().get(0)).getAsString();
        try {

            URI uri = Uris.parseOrNull(uriString);
            CqlTranslator translator = this.cqlTranslationManager.translate(uri);
            if (translator != null) {
                return CompletableFuture.completedFuture(isXml ? translator.toXml() : translator.toJson());
            }

            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
