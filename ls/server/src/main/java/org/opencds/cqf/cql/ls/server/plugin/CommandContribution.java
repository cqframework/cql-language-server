package org.opencds.cqf.cql.ls.server.plugin;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.ExecuteCommandParams;

public interface CommandContribution {
    default Set<String> getCommands() {
        return Collections.emptySet();
    }

    default CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        throw new RuntimeException(String.format("Unsupported Command: %s", params.getCommand()));
    }
}
