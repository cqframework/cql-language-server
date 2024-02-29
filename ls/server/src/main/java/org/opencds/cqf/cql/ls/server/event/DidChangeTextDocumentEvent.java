package org.opencds.cqf.cql.ls.server.event;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;

public class DidChangeTextDocumentEvent extends BaseEvent<DidChangeTextDocumentParams> {
    public DidChangeTextDocumentEvent(DidChangeTextDocumentParams params) {
        super(params);
    }
}
