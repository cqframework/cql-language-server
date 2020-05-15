package org.opencds.cqf.cql.ls.fhir;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.opencds.cqf.cql.ls.CqlUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.eclipse.lsp4j.TextDocumentItem;
import org.hl7.fhir.dstu3.model.Attachment;
import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.Library;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;

/**
 * Created by Bryn on 9/4/2018.
 */
public class FhirTextDocumentProvider {
    private static final Logger Log = LoggerFactory.getLogger(FhirTextDocumentProvider.class);
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

    public TextDocumentItem getDocument(URI uri) {

        try {
            URI baseUri = CqlUtilities.getFhirBaseUri(uri);

            IGenericClient fhirClient = this.fhirContext.newRestfulGenericClient(baseUri.toString());
            Library library = fhirClient.read().resource(Library.class).withUrl(uri.toString()).elementsSubset("name", "version", "content", "type").encodedJson().execute();

            if (library != null) {
                return extractTextDocument(uri, library);
            }
        }
        catch (Exception e) {
            Log.debug("failed to resolve {} with error: {}", uri.toString(), e.getMessage());
        }

        return null;
    }

    public TextDocumentItem getDocument(URI baseUri, String name, String version) {
        try {
            URI uri = CqlUtilities.getFhirBaseUri(baseUri);

            IGenericClient fhirClient = this.fhirContext.newRestfulGenericClient(uri.toString());

            Bundle result = fhirClient.search().forResource(Library.class).elementsSubset("name", "version").where(Library.NAME.matchesExactly().value(name))
                    .returnBundle(Bundle.class).encodedJson().execute();

            Library library = null;
            String libraryUrl = null;
            Library maxVersion = null;
            String maxUrl = null;
            if (result.hasEntry() && result.getEntry().size() > 0){
                for (Bundle.BundleEntryComponent bec : result.getEntry()) {
                    Library l = (Library)bec.getResource();
                    if ((version != null && l.getVersion().equals(version)) ||
                        (version == null && !l.hasVersion()))
                    {
                        library = l;
                        libraryUrl = bec.getFullUrl();
                    }
        
                    if (maxVersion == null || compareVersions(maxVersion.getVersion(), l.getVersion()) < 0){
                        maxVersion = l;
                        maxUrl = bec.getFullUrl();
                    }
                }
            }

            if (version == null && maxVersion != null) {
                library = maxVersion;
                libraryUrl = maxUrl;
            }

            // This is a subsetted resource, so we get the full version here.
            if (library != null) {
                return getDocument(new URI(libraryUrl));
            }

        }
        catch (Exception e) {
            Log.debug("resolve {} with error: {}",  name, e.getMessage());
        }

        return null;
    }

    private TextDocumentItem extractTextDocument(URI uri, Library library) {
        if (library.getType().getCoding().get(0).getCode().equals("logic-library")) {
            for (Attachment content : library.getContent()) {
                // TODO: Could use this for any content type, would require a mapping from content type to LanguageServer LanguageId
                if (content.getContentType().equals("text/cql")) {
                    TextDocumentItem textDocumentItem = new TextDocumentItem();
                    textDocumentItem.setUri(uri.toString());
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

    public static int compareVersions(String version1, String version2)
    {
        // Treat null as MAX VERSION
        if (version1 == null && version2 == null) {
            return 0;
        }

        if (version1 != null && version2 == null) {
            return -1;
        }

        if (version1 == null && version2 != null) {
            return 1;
        }

        String[] string1Vals = version1.split("\\.");
        String[] string2Vals = version2.split("\\.");
    
        int length = Math.max(string1Vals.length, string2Vals.length);
    
        for (int i = 0; i < length; i++)
        {
            Integer v1 = (i < string1Vals.length)?Integer.parseInt(string1Vals[i]):0;
            Integer v2 = (i < string2Vals.length)?Integer.parseInt(string2Vals[i]):0;
    
            //Making sure Version1 bigger than version2
            if (v1 > v2)
            {
                return 1;
            }
            //Making sure Version1 smaller than version2
            else if(v1 < v2)
            {
                return -1;
            }
        }
    
        //Both are equal
        return 0;
    }
}
