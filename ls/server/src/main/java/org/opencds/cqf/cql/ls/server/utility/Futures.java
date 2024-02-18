package org.opencds.cqf.cql.ls.server.utility;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.concurrent.CompletableFuture;

public class Futures {
    private Futures() {}

    public static <U> CompletableFuture<U> failed(Throwable ex) {
        checkNotNull(ex);
        CompletableFuture<U> future = new CompletableFuture<>();
        future.completeExceptionally(ex);
        return future;
    }
}
