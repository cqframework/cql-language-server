package org.opencds.cqf.cql.ls.plugin.debug;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.ExecuteCommandParams;
import org.opencds.cqf.cql.ls.FuturesHelper;
import org.opencds.cqf.cql.ls.manager.CqlTranslationManager;
import org.opencds.cqf.cql.ls.plugin.CommandContribution;
import org.opencds.cqf.cql.ls.plugin.debug.session.DebugSession;

public class DebugCommandContribution implements CommandContribution {

    public static final String START_DEBUG_COMMAND = "org.opencds.cqf.cql.ls.plugin.debug.startDebugSession";

    private DebugSession debugSession = null;

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
                if (this.debugSession == null || !this.debugSession.isActive()) {
                    this.debugSession = new DebugSession();
                    try {
                        return CompletableFuture.completedFuture(this.debugSession.start().get());
                    }
                    catch (Exception e) {
                        return FuturesHelper.failedFuture(e);
                    }
                }
                else {
                    throw new IllegalStateException(String.format("Please wait for the current debug session to end before starting a new one."));
                }
            default:
                throw new IllegalArgumentException(String.format("DebugPlugin doesn't support command %s", params.getCommand()));
        }
     }
}
