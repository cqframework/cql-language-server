package org.cqframework.cql;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.TextDocumentItem;

import java.net.URI;

/**
 * Created by Bryn on 9/4/2018.
 */
public interface TextDocumentProvider {
    Iterable<TextDocumentItem> getDocuments(String rootUri);
    TextDocumentItem getDocument(String uri);
}
