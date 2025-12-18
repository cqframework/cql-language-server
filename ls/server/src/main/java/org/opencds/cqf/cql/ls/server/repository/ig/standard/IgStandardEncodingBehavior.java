package org.opencds.cqf.cql.ls.server.repository.ig.standard;

import ca.uhn.fhir.rest.api.EncodingEnum;

/**
 * This class is used to determine how to handle encoding when reading and writing resources. You can
 * choose to preserve the encoding of the resource when reading and writing, or you can choose to change
 * the encoding to a preferred encoding when writing. New resources will always be written in the preferred
 * encoding.
 */
public class IgStandardEncodingBehavior {

    /**
     * When updating a resource, you can choose to preserve the original encoding of the resource
     * or you can choose to overwrite the original encoding with the preferred encoding.
     */
    public enum PreserveEncoding {
        PRESERVE_ORIGINAL_ENCODING,
        OVERWRITE_WITH_PREFERRED_ENCODING
    }

    public static final IgStandardEncodingBehavior DEFAULT = new IgStandardEncodingBehavior(
            EncodingEnum.JSON, IgStandardEncodingBehavior.PreserveEncoding.PRESERVE_ORIGINAL_ENCODING);

    private final EncodingEnum preferredEncoding;
    private final IgStandardEncodingBehavior.PreserveEncoding preserveEncoding;

    public IgStandardEncodingBehavior(
            EncodingEnum preferredEncoding, IgStandardEncodingBehavior.PreserveEncoding preserveEncoding) {
        this.preferredEncoding = preferredEncoding;
        this.preserveEncoding = preserveEncoding;
    }

    EncodingEnum preferredEncoding() {
        return preferredEncoding;
    }

    PreserveEncoding preserveEncoding() {
        return preserveEncoding;
    }
}
