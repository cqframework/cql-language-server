package org.opencds.cqf.cql.ls.server.event;

import org.eclipse.lsp4j.DidOpenTextDocumentParams;


public class DidOpenTextDocumentEvent extends Event<DidOpenTextDocumentParams> {
    public DidOpenTextDocumentEvent(DidOpenTextDocumentParams params) {
        super(params);
    }
}
