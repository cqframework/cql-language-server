package org.opencds.cqf.cql.ls.plugin;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.ExecuteCommandParams;

public interface CommandContribution {
    Set<String> getCommands();
    CompletableFuture<Object> executeCommand(ExecuteCommandParams params);
}
