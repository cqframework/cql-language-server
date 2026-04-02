package org.opencds.cqf.cql.debug

import org.eclipse.lsp4j.debug.services.IDebugProtocolClient

interface IDebugProtocolClientAware {
    fun connect(client: IDebugProtocolClient)
}
