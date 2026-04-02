package org.opencds.cqf.cql.ls.server.utility

import java.util.concurrent.CompletableFuture

object Futures {
    @JvmStatic
    fun <U> failed(ex: Throwable): CompletableFuture<U> {
        requireNotNull(ex)
        val future = CompletableFuture<U>()
        future.completeExceptionally(ex)
        return future
    }
}
