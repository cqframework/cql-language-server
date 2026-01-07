package org.opencds.cqf.cql.ls.server.provider;

import java.io.*;
import java.net.URI;
import org.hl7.cql.model.ModelIdentifier;
import org.hl7.cql.model.ModelInfoProvider;
import org.hl7.elm_modelinfo.r1.ModelInfo;
import org.hl7.elm_modelinfo.r1.serializing.XmlModelInfoReaderKt;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.core.utility.Converters;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
                URI modelUri = Uris.addPath(
                        root,
                        String.format(
                                "/%s-modelinfo%s.xml",
                                modelName.toLowerCase(), modelVersion != null ? ("-" + modelVersion) : ""));
                InputStream modelInputStream = contentService.read(modelUri);
                if (modelInputStream != null) {
                    return XmlModelInfoReaderKt.parseModelInfoXml(Converters.inputStreamToString(modelInputStream));
                }
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        String.format("Could not load definition for model info %s.", modelIdentifier.getId()), e);
            }
        }

        return null;
    }
}
