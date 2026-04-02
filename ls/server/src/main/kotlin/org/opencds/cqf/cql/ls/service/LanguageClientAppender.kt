package org.opencds.cqf.cql.ls.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.services.LanguageClient

class LanguageClientAppender(private val client: LanguageClient) : AppenderBase<ILoggingEvent>() {
    override fun append(eventObject: ILoggingEvent?) {
        if (eventObject == null) return
        client.logMessage(createMessageParams(eventObject))
    }

    internal fun createMessageParams(eventObject: ILoggingEvent): MessageParams =
        MessageParams(toType(eventObject.level), eventObject.formattedMessage)

    internal fun toType(level: Level): MessageType =
        when (level) {
            Level.ERROR -> MessageType.Error
            Level.WARN -> MessageType.Warning
            Level.INFO -> MessageType.Info
            else -> MessageType.Log
        }
}
