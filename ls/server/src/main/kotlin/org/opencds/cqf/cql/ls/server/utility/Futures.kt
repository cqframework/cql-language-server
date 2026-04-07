package org.opencds.cqf.cql.ls.server.utility

import java.util.concurrent.CompletableFuture

object Futures {
    fun <U> failed(ex: Throwable): CompletableFuture<U> = CompletableFuture<U>().also { it.completeExceptionally(ex) }
}
