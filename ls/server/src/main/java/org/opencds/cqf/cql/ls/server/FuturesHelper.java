package org.opencds.cqf.cql.ls.server;

import static com.google.common.base.Preconditions.checkNotNull;
import java.util.concurrent.CompletableFuture;

public class FuturesHelper {
    private FuturesHelper() {

    }

    public static <U> CompletableFuture<U> failedFuture(Throwable ex) {
        checkNotNull(ex);
        CompletableFuture<U> future = new CompletableFuture<>();
        future.completeExceptionally(ex);
        return future;
    }
}
