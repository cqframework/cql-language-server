package org.opencds.cqf.cql.ls.server.repository.ig.standard

import ca.uhn.fhir.rest.api.EncodingEnum

/**
 * This class is used to determine how to handle encoding when reading and writing resources. You can
 * choose to preserve the encoding of the resource when reading and writing, or you can choose to change
 * the encoding to a preferred encoding when writing. New resources will always be written in the preferred
 * encoding.
 */
class IgStandardEncodingBehavior(
    internal val preferredEncoding: EncodingEnum,
    internal val preserveEncoding: PreserveEncoding,
) {
    /**
     * When updating a resource, you can choose to preserve the original encoding of the resource
     * or you can choose to overwrite the original encoding with the preferred encoding.
     */
    enum class PreserveEncoding {
        PRESERVE_ORIGINAL_ENCODING,
        OVERWRITE_WITH_PREFERRED_ENCODING,
    }

    companion object {
        @JvmField
        val DEFAULT =
            IgStandardEncodingBehavior(
                EncodingEnum.JSON,
                PreserveEncoding.PRESERVE_ORIGINAL_ENCODING,
            )
    }
}
