package org.opencds.cqf.cql.ls.server.event

import org.eclipse.lsp4j.DidCloseTextDocumentParams

class DidCloseTextDocumentEvent(params: DidCloseTextDocumentParams) : BaseEvent<DidCloseTextDocumentParams>(params)
