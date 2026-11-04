package org.opencds.cqf.cql.ls.server.validation.profile

import org.hl7.fhir.r5.context.IWorkerContext
import org.hl7.fhir.r5.elementmodel.Element
import org.hl7.fhir.r5.elementmodel.Manager
import org.hl7.fhir.r5.model.CanonicalResource
import org.hl7.fhir.r5.model.CanonicalType
import org.hl7.fhir.r5.utils.validation.IResourceValidator
import org.hl7.fhir.r5.utils.validation.IValidatorResourceFetcher
import org.hl7.fhir.validation.instance.InstanceValidator
import java.util.Locale

internal fun isAbsoluteUrl(url: String?): Boolean =
    url != null && (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("urn:oid:"))

/**
 * Resolves references only from [index], never from the network. Absolute URLs (canonicals,
 * system URIs, external references) are treated as resolvable and never fetched. Not thread-safe:
 * [index] is per call, so callers serialize use of one engine.
 */
class NoNetworkFetcher : IValidatorResourceFetcher {
    var index: CaseResourceIndex = CaseResourceIndex.EMPTY

    override fun fetch(
        validator: IResourceValidator?,
        appContext: Any?,
        url: String?,
    ): Element? {
        if (url == null || isAbsoluteUrl(url)) return null
        val json = index.lookupJson(url) ?: return null
        val ctx: IWorkerContext = (validator as? InstanceValidator)?.context ?: return null
        return Manager.parseSingle(ctx, json.byteInputStream(), Manager.FhirFormat.JSON)
    }

    override fun resolveURL(
        validator: IResourceValidator?,
        appContext: Any?,
        path: String?,
        url: String?,
        rules: IWorkerContext.VersionResolutionRules?,
        type: String?,
        canonical: Boolean,
        targets: MutableList<CanonicalType>?,
    ): Boolean {
        if (url == null) return false
        if (isAbsoluteUrl(url) || (url.startsWith("urn:") && !url.startsWith("urn:uuid:"))) return true
        return index.lookup(url) != null
    }

    override fun fetchRaw(
        validator: IResourceValidator?,
        url: String?,
    ): ByteArray? = null

    override fun setLocale(locale: Locale?): IValidatorResourceFetcher = this

    override fun fetchCanonicalResource(
        validator: IResourceValidator?,
        appContext: Any?,
        url: String?,
    ): CanonicalResource? = null

    override fun fetchesCanonicalResource(
        validator: IResourceValidator?,
        url: String?,
    ): Boolean = false

    override fun fetchCanonicalResourceVersions(
        validator: IResourceValidator?,
        appContext: Any?,
        url: String?,
    ): MutableSet<IValidatorResourceFetcher.ResourceVersionInformation> = mutableSetOf()
}
