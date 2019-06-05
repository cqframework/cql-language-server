package org.cqframework.cql.source;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import org.cqframework.cql.CqlWorkspaceService;
import org.cqframework.cql.cql2elm.LibrarySourceProvider;
import org.cqframework.cql.fhir.FhirTextDocumentProvider;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.hl7.elm.r1.VersionedIdentifier;


// LibrarySourceProvider implementation that pulls from a fhir server
public class FhirServerLibrarySourceProvider implements LibrarySourceProvider {
    private static final Logger LOG = Logger.getLogger("main");

    private final CqlWorkspaceService workspaceService;
    private final FhirTextDocumentProvider textDocumentProvider;

    public FhirServerLibrarySourceProvider(CqlWorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
        this.textDocumentProvider = new FhirTextDocumentProvider();
    }

    @Override
    public InputStream getLibrarySource(VersionedIdentifier versionedIdentifier) {

        for (WorkspaceFolder f : this.workspaceService.getWorkspaceFolders()) {

            // Only search remote urls
            if (!f.getUri().startsWith("http")) {
                continue;
            }

            TextDocumentItem textDocumentItem = textDocumentProvider.getDocument(f.getUri(), 
                versionedIdentifier.getId(), 
                versionedIdentifier.getVersion());

            if (textDocumentItem != null) {
                return new ByteArrayInputStream(textDocumentItem.getText().getBytes(StandardCharsets.UTF_8));
            }
        }

        return null;
    }
}