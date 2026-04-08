package org.opencds.cqf.cql.ls.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class LanguageClientAppenderTest {
    private val client = mock(LanguageClient::class.java)
    private val appender =
        LanguageClientAppender(client).also { it.start() } // start() required for doAppend to invoke append()

    // -----------------------------------------------------------------------
    // doAppend(null) — null event is silently ignored (append() early-returns)
    // -----------------------------------------------------------------------

    @Test
    fun doAppend_nullEvent_doesNotCallClient() {
        appender.doAppend(null)
        verify(client, never()).logMessage(MessageParams(MessageType.Log, ""))
    }

    // -----------------------------------------------------------------------
    // doAppend — non-null event forwards to client.logMessage via append()
    // -----------------------------------------------------------------------

    @Test
    fun doAppend_infoEvent_callsClientLogMessage() {
        val event = mock(ILoggingEvent::class.java)
        `when`(event.level).thenReturn(Level.INFO)
        `when`(event.formattedMessage).thenReturn("hello world")
        appender.doAppend(event)
        verify(client).logMessage(MessageParams(MessageType.Info, "hello world"))
    }

    // -----------------------------------------------------------------------
    // toType — all four cases
    // -----------------------------------------------------------------------

    @Test
    fun toType_error_returnsError() {
        assertEquals(MessageType.Error, appender.toType(Level.ERROR))
    }

    @Test
    fun toType_warn_returnsWarning() {
        assertEquals(MessageType.Warning, appender.toType(Level.WARN))
    }

    @Test
    fun toType_info_returnsInfo() {
        assertEquals(MessageType.Info, appender.toType(Level.INFO))
    }

    @Test
    fun toType_debug_returnsLog() {
        assertEquals(MessageType.Log, appender.toType(Level.DEBUG))
    }

    @Test
    fun toType_trace_returnsLog() {
        assertEquals(MessageType.Log, appender.toType(Level.TRACE))
    }

    // -----------------------------------------------------------------------
    // createMessageParams — message text and type match the event
    // -----------------------------------------------------------------------

    @Test
    fun createMessageParams_warnEvent_buildsCorrectParams() {
        val event = mock(ILoggingEvent::class.java)
        `when`(event.level).thenReturn(Level.WARN)
        `when`(event.formattedMessage).thenReturn("something went wrong")
        val params = appender.createMessageParams(event)
        assertEquals(MessageType.Warning, params.type)
        assertEquals("something went wrong", params.message)
    }

    @Test
    fun createMessageParams_errorEvent_buildsCorrectParams() {
        val event = mock(ILoggingEvent::class.java)
        `when`(event.level).thenReturn(Level.ERROR)
        `when`(event.formattedMessage).thenReturn("fatal error")
        val params = appender.createMessageParams(event)
        assertEquals(MessageType.Error, params.type)
        assertEquals("fatal error", params.message)
    }
}
