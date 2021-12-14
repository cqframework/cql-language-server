package org.opencds.cqf.cql.ls.event;

import org.eclipse.lsp4j.DidChangeWatchedFilesParams;

public class DidChangeWatchedFilesEvent {

    private DidChangeWatchedFilesParams didChangeWatchedFilesParams;

    public DidChangeWatchedFilesEvent(DidChangeWatchedFilesParams didChangeWatchedFilesParams) {
        this.didChangeWatchedFilesParams = didChangeWatchedFilesParams;
    }

    public DidChangeWatchedFilesParams getParams() {
        return this.didChangeWatchedFilesParams;
    }
}
