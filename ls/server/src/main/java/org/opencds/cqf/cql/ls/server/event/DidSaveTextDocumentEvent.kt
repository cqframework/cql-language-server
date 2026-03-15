package org.opencds.cqf.cql.ls.server.event

import org.eclipse.lsp4j.DidSaveTextDocumentParams

class DidSaveTextDocumentEvent(params: DidSaveTextDocumentParams) : BaseEvent<DidSaveTextDocumentParams>(params)
