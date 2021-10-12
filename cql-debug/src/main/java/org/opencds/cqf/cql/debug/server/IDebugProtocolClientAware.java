package org.opencds.cqf.cql.debug.server;

import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;

public interface IDebugProtocolClientAware {
    void connect(IDebugProtocolClient client);
}
