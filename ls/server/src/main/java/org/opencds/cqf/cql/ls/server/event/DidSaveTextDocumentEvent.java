package org.opencds.cqf.cql.ls.server.event;

import org.eclipse.lsp4j.DidSaveTextDocumentParams;


public class DidSaveTextDocumentEvent extends Event<DidSaveTextDocumentParams> {
    public DidSaveTextDocumentEvent(DidSaveTextDocumentParams params) {
        super(params);
    }
}
