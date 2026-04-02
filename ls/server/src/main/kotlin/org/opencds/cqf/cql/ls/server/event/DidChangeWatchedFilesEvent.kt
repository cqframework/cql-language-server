package org.opencds.cqf.cql.ls.server.event

import org.eclipse.lsp4j.DidChangeWatchedFilesParams

class DidChangeWatchedFilesEvent(params: DidChangeWatchedFilesParams) : BaseEvent<DidChangeWatchedFilesParams>(params)
