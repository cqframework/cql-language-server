package org.opencds.cqf.cql.ls;

import java.util.concurrent.CompletableFuture;

public class FuturesHelper {
    public static <U> CompletableFuture<U> failedFuture(Throwable ex) {
        if (ex == null) throw new NullPointerException();
        CompletableFuture<U> future = new CompletableFuture<>();
        future.completeExceptionally(ex);
        return future;
    }
}
