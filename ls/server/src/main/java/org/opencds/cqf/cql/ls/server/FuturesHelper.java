package org.opencds.cqf.cql.ls.server;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class FuturesHelper {
    private FuturesHelper() {

    }

    public static <U> CompletableFuture<U> failedFuture(Throwable ex) {
        Objects.requireNonNull(ex);
        CompletableFuture<U> future = new CompletableFuture<>();
        future.completeExceptionally(ex);
        return future;
    }
}
