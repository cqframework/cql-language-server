package org.opencds.cqf.cql.ls.server.event;

import org.eclipse.lsp4j.DidOpenTextDocumentParams;

public class DidOpenTextDocumentEvent extends BaseEvent<DidOpenTextDocumentParams> {
    public DidOpenTextDocumentEvent(DidOpenTextDocumentParams params) {
        super(params);
    }
}
