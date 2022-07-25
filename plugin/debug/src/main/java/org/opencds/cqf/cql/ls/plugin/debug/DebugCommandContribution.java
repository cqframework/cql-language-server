package org.opencds.cqf.cql.ls.plugin.debug;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.opencds.cqf.cql.ls.plugin.debug.session.DebugSession;
import org.opencds.cqf.cql.ls.server.manager.CqlTranslationManager;
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution;
import org.opencds.cqf.cql.ls.server.utility.Futures;

public class DebugCommandContribution implements CommandContribution {

    public static final String START_DEBUG_COMMAND =
            "org.opencds.cqf.cql.ls.plugin.debug.startDebugSession";

    private DebugSession debugSession = null;

    private CqlTranslationManager cqlTranslationManager;

    public DebugCommandContribution(CqlTranslationManager cqlTranslationManager) {
        this.cqlTranslationManager = cqlTranslationManager;

        // Temporary hack for unused variable
        this.cqlTranslationManager.hashCode();
    }

    @Override
    public Set<String> getCommands() {
        return Collections.singleton(START_DEBUG_COMMAND);
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        if (START_DEBUG_COMMAND.equals(params.getCommand())) {
            if (this.debugSession == null || !this.debugSession.isActive()) {
                this.debugSession = new DebugSession();
                try {
                    return CompletableFuture.completedFuture(this.debugSession.start().get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Futures.failed(e);
                } catch (Exception e) {
                    return Futures.failed(e);
                }
            } else {
                throw new IllegalStateException(
                        "Please wait for the current debug session to end before starting a new one.");
            }
        } else {
            throw new IllegalArgumentException(
                    String.format("DebugPlugin doesn't support command %s", params.getCommand()));
        }
    }
}
