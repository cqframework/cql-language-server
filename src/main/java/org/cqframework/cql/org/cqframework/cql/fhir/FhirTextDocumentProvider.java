package org.cqframework.cql.org.cqframework.cql.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.SummaryEnum;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.IQuery;

import org.cqframework.cql.CqlUtilities;
import org.cqframework.cql.TextDocumentProvider;
import org.eclipse.lsp4j.TextDocumentItem;
import org.hl7.fhir.dstu3.model.Attachment;
import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.Library;
import org.hl7.fhir.instance.model.api.IBaseBundle;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import com.google.common.net.UrlEscapers;

/**
 * Created by Bryn on 9/4/2018.
 */
public class FhirTextDocumentProvider implements TextDocumentProvider {
    protected FhirContext fhirContext;

    /*
         const data = {
            "resourceType": "Library",
            "version": "1.0.0",
            "status": "draft",
            "id" : id,
            "type": {
              "coding": [
                {
                  "code": "logic-library"
                }
              ]
            },
            "content": [
              {
                "contentType": "text/cql",
                "data": content
              }
            ]
        };
    */

    public FhirTextDocumentProvider() {
        this.fhirContext = FhirContext.forDstu3();
    }

    @Override
    public Collection<URI> getDocuments(String baseUri) {
        IGenericClient fhirClient = this.fhirContext.newRestfulGenericClient(baseUri);
        IQuery<IBaseBundle> search = fhirClient.search().byUrl("Library").summaryMode(SummaryEnum.TRUE);
        Bundle results = search.returnBundle(Bundle.class).execute();
        HashSet<URI> uris = new HashSet<URI>();

        updateUris(baseUri, results, uris);
        while (results.getLink(IBaseBundle.LINK_NEXT) != null) {
            results = fhirClient.loadPage().next(results).execute();
            updateUris(baseUri, results, uris);
        }

        return uris;
    }


    private void updateUris(String baseUri, Bundle bundle, Set<URI> uris) {
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            Library library = (Library)entry.getResource();
            if (library.getType().getCoding().get(0).getCode().equals("logic-library")) {
                try {
                    String id = library.getId();

                    // handles base uri being prefixed
                    int index = id.lastIndexOf("/Library/");
                    if (index > 0) {
                        id = id.substring(index + 9);
                    }

                    // handles _history being postfixed
                    index = id.indexOf("/");
                    if (index > 0) {
                        id = id.substring(0, index);
                    }

                    uris.add(new URI(baseUri + "/Library/" + id));
                }
                catch (Exception e) {
                    // Nothing...
                }
            }
        }
    }

    @Override
    public TextDocumentItem getDocument(String uri) {
        String baseUri = CqlUtilities.getLibraryBaseUri(uri);

        IGenericClient fhirClient = this.fhirContext.newRestfulGenericClient(baseUri);
        Library library = fhirClient.read().resource(Library.class).withUrl(uri).execute();

        if (library != null) {
            return extractTextDocument(uri, library);
        }

        return null;
    }



    @Override
    public TextDocumentItem getDocument(String baseUri, String idOrName) {

        IGenericClient fhirClient = this.fhirContext.newRestfulGenericClient(baseUri);
        Library library = null;
        try {
            library = fhirClient.read().resource(Library.class).withId(idOrName).execute();
        }
        catch (Exception e) {}

        if (library == null ) {
            Bundle result = fhirClient.search().forResource(Library.class).where(Library.NAME.matches().value(idOrName))
                .returnBundle(Bundle.class).execute();

            if (result.hasEntry() && result.getEntry().size() > 0){
                try {
                    library = fhirClient.read().resource(Library.class)
                    .withId(result.getEntry().get(0).getResource().getId()).execute();
                }
                catch (Exception e) {}
            }
        }
        if (library != null) {
            return extractTextDocument(baseUri + "/Library/" + idOrName, library);
        }

        return null;
    }

    private TextDocumentItem extractTextDocument(String uri, Library library) {
        if (library.getType().getCoding().get(0).getCode().equals("logic-library")) {
            for (Attachment content : library.getContent()) {
                // TODO: Could use this for any content type, would require a mapping from content type to LanguageServer LanguageId
                if (content.getContentType().equals("text/cql")) {
                    TextDocumentItem textDocumentItem = new TextDocumentItem();
                    textDocumentItem.setUri(uri);
                    textDocumentItem.setVersion(0); // TODO: Cannot assume version of the resource is tracked and/or relevant without making assumptions about the FHIR Server...
                    textDocumentItem.setLanguageId("cql");
                    textDocumentItem.setText(new String(content.getData(), StandardCharsets.UTF_8));
                    return textDocumentItem;
                }
                // TODO: Decompile ELM if no CQL is available?
            }
        }

        return null;
    }
}
