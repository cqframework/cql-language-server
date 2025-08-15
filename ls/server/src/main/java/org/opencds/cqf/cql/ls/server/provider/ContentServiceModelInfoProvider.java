package org.opencds.cqf.cql.ls.server.provider;

import org.cqframework.cql.cql2elm.LibrarySourceProvider;
import org.cqframework.cql.cql2elm.model.Version;
import org.hl7.cql.model.ModelIdentifier;
import org.hl7.cql.model.ModelInfoProvider;
import org.hl7.elm.r1.VersionedIdentifier;
import org.hl7.elm_modelinfo.r1.ModelInfo;
import org.hl7.elm_modelinfo.r1.serializing.ModelInfoReaderFactory;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.nio.file.Path;

public class ContentServiceModelInfoProvider implements ModelInfoProvider {

    private static final Logger log = LoggerFactory.getLogger(ContentServiceModelInfoProvider.class);

    private final ContentService contentService;
    private final URI root;

    public ContentServiceModelInfoProvider(URI root, ContentService contentService) {
        this.contentService = contentService;
        this.root = root;
    }

    public ModelInfo load(ModelIdentifier modelIdentifier) {
        if (root != null && contentService != null) {
            String modelName = modelIdentifier.getId();
            String modelVersion = modelIdentifier.getVersion();

            try {
                URI modelUri = Uris.addPath(root, String.format(
                        "/%s-modelinfo%s.xml", modelName.toLowerCase(), modelVersion != null ? ("-" + modelVersion) : ""));
                InputStream modelInputStream = contentService.read(modelUri);
                if (modelInputStream != null) {
                    return ModelInfoReaderFactory.getReader("application/xml").read(modelInputStream);
                }
            }
            catch (IOException e) {
                throw new IllegalArgumentException(
                        String.format("Could not load definition for model info %s.", modelIdentifier.getId()), e);
            }

            try {
                URI modelUri = Uris.addPath(root, String.format(
                        "/%s-modelinfo%s.json", modelName.toLowerCase(), modelVersion != null ? ("-" + modelVersion) : ""));
                InputStream modelInputStream = contentService.read(modelUri);
                if (modelInputStream != null) {
                    return ModelInfoReaderFactory.getReader("application/json").read(modelInputStream);
                }
            }
            catch (IOException e) {
                throw new IllegalArgumentException(
                        String.format("Could not load definition for model info %s.", modelIdentifier.getId()), e);
            }
        }

        return null;
    }
}
