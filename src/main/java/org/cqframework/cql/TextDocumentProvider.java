package org.cqframework.cql;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.TextDocumentItem;

import java.net.URI;
import java.util.Collection;

/**
 * Created by Bryn on 9/4/2018.
 */
public interface TextDocumentProvider {
    Collection<URI> getDocuments(String rootUri);
    TextDocumentItem getDocument(String rootUri, String idOrName);
    TextDocumentItem getDocument(String uri);
}
