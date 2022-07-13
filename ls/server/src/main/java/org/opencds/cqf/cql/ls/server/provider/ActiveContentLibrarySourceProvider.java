package org.opencds.cqf.cql.ls.server.provider;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map.Entry;

import org.cqframework.cql.cql2elm.LibrarySourceProvider;
import org.hl7.elm.r1.VersionedIdentifier;
import org.opencds.cqf.cql.ls.server.ActiveContent;
import org.opencds.cqf.cql.ls.server.CqlUtilities;
import org.opencds.cqf.cql.ls.server.VersionedContent;


// LibrarySourceProvider implementation that pulls from the active content
public class ActiveContentLibrarySourceProvider implements LibrarySourceProvider {
    // private static final Logger LOG = Logger.getLogger("main");

    private final URI baseUri;
    private final ActiveContent activeContent;

    public ActiveContentLibrarySourceProvider(URI baseUri, ActiveContent activeContent) {
        this.baseUri = baseUri;
        this.activeContent = activeContent;
    }

    @Override
    public InputStream getLibrarySource(VersionedIdentifier versionedIdentifier) {
        String id = versionedIdentifier.getId();
        String version = versionedIdentifier.getVersion();

        String matchText = "(?s).*library\\s+" + id;
        if (version != null) {
            matchText += ("\\s+version\\s+'" + version + "'\\s+(?s).*");
        }
        else {
            matchText += "'\\s+(?s).*";
        }

        for(Entry<URI, VersionedContent> uri : this.activeContent.entrySet()){

            URI root = CqlUtilities.getHead(uri.getKey());
            if (!root.equals(this.baseUri)) {
                continue;
            }
            
            String content = uri.getValue().content;
            // This will match if the content contains the library definition is present.
            if (content.matches(matchText)){
                return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            }
        }

        return null;
    }
}