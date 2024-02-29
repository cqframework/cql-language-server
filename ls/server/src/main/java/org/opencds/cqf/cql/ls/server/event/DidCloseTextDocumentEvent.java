package org.opencds.cqf.cql.ls.server.event;

import org.eclipse.lsp4j.DidCloseTextDocumentParams;

public class DidCloseTextDocumentEvent extends BaseEvent<DidCloseTextDocumentParams> {
    public DidCloseTextDocumentEvent(DidCloseTextDocumentParams params) {
        super(params);
    }
}
