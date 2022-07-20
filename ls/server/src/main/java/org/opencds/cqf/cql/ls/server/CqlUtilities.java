package org.opencds.cqf.cql.ls.server;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;

import org.cqframework.cql.cql2elm.CqlTranslator;
import org.cqframework.cql.cql2elm.CqlTranslatorOptions;
import org.cqframework.cql.cql2elm.CqlTranslatorOptionsMapper;
import org.cqframework.cql.cql2elm.LibraryBuilder.SignatureLevel;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by Bryn on 9/4/2018.
 */
public class CqlUtilities {

    private CqlUtilities() {
    }

    private static final Logger log = LoggerFactory.getLogger(CqlUtilities.class);

    public static CqlTranslatorOptions getTranslatorOptions(ContentService contentService, URI uri) {

        CqlTranslatorOptions options = null;

        InputStream input = contentService.read(Uris.addPath(Uris.getHead(uri), "/cql-options.json"));

        if (input != null) {
            options = CqlTranslatorOptionsMapper.fromReader(new InputStreamReader(input));
        }
        else {
            log.info("cql-options.json not found, using default options");
            options = CqlTranslatorOptions.defaultOptions();
        }

        if (!options.getFormats().contains(CqlTranslator.Format.XML)) {
            options.getFormats().add(CqlTranslator.Format.XML);
        }

        // For the purposes of debugging and authoring support, always add detailed
        // translation information.
        return options
                .withOptions(CqlTranslator.Options.EnableLocators, CqlTranslator.Options.EnableResultTypes,
                        CqlTranslator.Options.EnableAnnotations)
                .withSignatureLevel(SignatureLevel.All);
    }
}
