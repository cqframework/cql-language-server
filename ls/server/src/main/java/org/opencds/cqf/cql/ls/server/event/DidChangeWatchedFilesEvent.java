package org.opencds.cqf.cql.ls.server.event;

import org.eclipse.lsp4j.DidChangeWatchedFilesParams;

public class DidChangeWatchedFilesEvent extends Event<DidChangeWatchedFilesParams> {
    public DidChangeWatchedFilesEvent(DidChangeWatchedFilesParams params) {
        super(params);
    }
}
