package org.opencds.cqf.cql.ls.debug.server;

import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;

public interface IDebugProtocolClientAware {
    void connect(IDebugProtocolClient client);
}
