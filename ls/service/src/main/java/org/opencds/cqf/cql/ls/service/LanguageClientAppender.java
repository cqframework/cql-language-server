package org.opencds.cqf.cql.ls.service;

import java.util.Objects;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.services.LanguageClient;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * This class allows logging to the LSP client's {@code} logMessage} API
 */
public class LanguageClientAppender extends AppenderBase<ILoggingEvent> {
    private final LanguageClient client;

    /**
     * Constructs a LanguageClientAppender
     *
     * @param client the client to log to
     */
    public LanguageClientAppender(LanguageClient client) {
        this.client = client;
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        if (eventObject == null) {
            return;
        }

        this.client.logMessage(createMessageParams(eventObject));
    }

    MessageParams createMessageParams(ILoggingEvent eventObject) {
        Objects.requireNonNull(eventObject);

        return new MessageParams(toType(eventObject.getLevel()), eventObject.getMessage());
    }

    MessageType toType(Level level) {
        if (level == Level.ERROR) {
            return MessageType.Error;
        } else if (level == Level.WARN) {
            return MessageType.Warning;
        } else if (level == Level.INFO) {
            return MessageType.Info;
        } else {
            return MessageType.Log;
        }
    }
}
