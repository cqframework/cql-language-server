package org.opencds.cqf.cql.ls.plugin.debug;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.ExecuteCommandParams;
import org.opencds.cqf.cql.ls.manager.CqlTranslationManager;
import org.opencds.cqf.cql.ls.plugin.CommandContribution;

public class DebugCommandContribution implements CommandContribution {

    public static final String START_DEBUG_COMMAND = "org.opencds.cqf.cql.ls.plugin.debug.startDebugSession";

    private CqlTranslationManager cqlTranslationManager;

    public DebugCommandContribution(CqlTranslationManager cqlTranslationManager) {
        this.cqlTranslationManager = cqlTranslationManager;
    }

    @Override
    public Set<String> getCommands() {
        return Collections.singleton(START_DEBUG_COMMAND);
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {

        this.cqlTranslationManager.hashCode();

        switch(params.getCommand()) {
            case START_DEBUG_COMMAND:
                // TODO: Start Debug Server, return port
                return CompletableFuture.completedFuture(42);
            default:
                throw new IllegalArgumentException(String.format("DebugPlugin doesn't support command %s", params.getCommand()));
        }
     }
}
