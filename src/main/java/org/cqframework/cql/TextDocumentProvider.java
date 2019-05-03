package org.cqframework.cql;

import org.eclipse.lsp4j.TextDocumentItem;

/**
 * Created by Bryn on 9/4/2018.
 */
public interface TextDocumentProvider {
    TextDocumentItem getDocument(String rootUri, String name, String version);
    TextDocumentItem getDocument(String uri);
}
