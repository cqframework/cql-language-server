package org.opencds.cqf.cql.ls.server.utility

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ExecutionException

class FuturesTest {
    @Test
    fun failed_returnsCompletedExceptionally() {
        val future = Futures.failed<String>(RuntimeException("test"))
        assertTrue(future.isCompletedExceptionally)
    }

    @Test
    fun failed_wrapsExceptionAsCause() {
        val ex = RuntimeException("test error")
        val future = Futures.failed<Int>(ex)
        val thrown = assertThrows<ExecutionException> { future.get() }
        assertEquals(ex, thrown.cause)
    }
}
