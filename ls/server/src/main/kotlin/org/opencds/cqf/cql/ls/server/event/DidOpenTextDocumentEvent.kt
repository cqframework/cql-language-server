package org.opencds.cqf.cql.ls.server.event

import org.eclipse.lsp4j.DidOpenTextDocumentParams

class DidOpenTextDocumentEvent(params: DidOpenTextDocumentParams) : BaseEvent<DidOpenTextDocumentParams>(params)
