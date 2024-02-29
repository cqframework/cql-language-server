package org.opencds.cqf.cql.ls.server.event;

import org.eclipse.lsp4j.DidSaveTextDocumentParams;

public class DidSaveTextDocumentEvent extends BaseEvent<DidSaveTextDocumentParams> {
    public DidSaveTextDocumentEvent(DidSaveTextDocumentParams params) {
        super(params);
    }
}
