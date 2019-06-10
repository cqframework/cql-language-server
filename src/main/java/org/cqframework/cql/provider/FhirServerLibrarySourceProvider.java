package org.cqframework.cql.provider;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import org.cqframework.cql.cql2elm.LibrarySourceProvider;
import org.cqframework.cql.fhir.FhirTextDocumentProvider;
import org.eclipse.lsp4j.TextDocumentItem;
import org.hl7.elm.r1.VersionedIdentifier;


// LibrarySourceProvider implementation that pulls from a fhir server
public class FhirServerLibrarySourceProvider implements LibrarySourceProvider {
    private static final Logger LOG = Logger.getLogger("main");

    private final FhirTextDocumentProvider textDocumentProvider;

    private final URI baseUri;

    public FhirServerLibrarySourceProvider(URI baseUri) {
        this.baseUri = baseUri;
        this.textDocumentProvider = new FhirTextDocumentProvider();
    }

    @Override
    public InputStream getLibrarySource(VersionedIdentifier versionedIdentifier) {

        if (!this.baseUri.getScheme().startsWith("http")) {
            return null;
        }

        TextDocumentItem textDocumentItem = textDocumentProvider.getDocument(baseUri, 
            versionedIdentifier.getId(), 
            versionedIdentifier.getVersion());

        if (textDocumentItem != null) {
            return new ByteArrayInputStream(textDocumentItem.getText().getBytes(StandardCharsets.UTF_8));
        }

        return null;
    }
}