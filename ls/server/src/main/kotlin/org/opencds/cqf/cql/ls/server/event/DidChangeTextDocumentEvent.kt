package org.opencds.cqf.cql.ls.server.event

import org.eclipse.lsp4j.DidChangeTextDocumentParams

class DidChangeTextDocumentEvent(params: DidChangeTextDocumentParams) : BaseEvent<DidChangeTextDocumentParams>(params)
