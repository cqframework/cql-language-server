package org.opencds.cqf.cql.ls.server.manager

import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.quick.FhirLibrarySourceProvider
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.ls.core.utility.Converters
import org.slf4j.LoggerFactory

/**
 * Resolves a library's CQL source text for identifiers that have no workspace file — bundled
 * libraries (e.g. FHIRHelpers, a classpath resource inside the "quick" jar) or npm-installed
 * libraries. Shared by [org.opencds.cqf.cql.debug.CqlDebugServer]'s DAP `source()` fallback and
 * [org.opencds.cqf.cql.ls.server.provider.DefinitionProvider]'s go-to-definition fallback.
 */
object LibrarySourceResolver {
    private val log = LoggerFactory.getLogger(LibrarySourceResolver::class.java)

    /**
     * Some identifiers arrive with the FULL namespaced URI baked into `.id` itself (e.g.
     * "http://hl7.org/fhir/uv/cql/FHIRHelpers" instead of "FHIRHelpers", with `.system` left
     * null) — a shape neither the provider chain nor [FhirLibrarySourceProvider]'s classpath
     * lookup (which builds "<id>-<version>.cql") can resolve, since none of them expect a
     * slash-qualified id. Normalize to the bare trailing segment and also try that.
     */
    fun candidateIdentifiers(id: VersionedIdentifier): List<VersionedIdentifier> {
        val bareId = id.id?.substringAfterLast('/')
        val normalizedId =
            if (bareId != null && bareId != id.id) {
                VersionedIdentifier().also {
                    it.id = bareId
                    it.version = id.version
                    it.system = id.system
                }
            } else {
                null
            }
        return listOfNotNull(id, normalizedId)
    }

    /**
     * Resolves source text via [libraryManager]'s registered provider chain, falling back to a
     * fresh, stateless [FhirLibrarySourceProvider] (a pure classpath-resource lookup keyed only
     * by id+version) so bundled-library resolution can't be affected by whatever
     * provider-registration/ordering issue might affect [libraryManager]'s own chain.
     */
    fun resolve(
        libraryManager: LibraryManager?,
        id: VersionedIdentifier,
    ): String? {
        val candidateIds = candidateIdentifiers(id)

        val providerChainContent =
            candidateIds.firstNotNullOfOrNull { candidate ->
                try {
                    val source = libraryManager?.librarySourceLoader?.getLibrarySource(candidate)
                    log.debug(
                        "resolve: id={} librarySourceLoader.getLibrarySource returned {}",
                        candidate,
                        if (source != null) "a Source" else "null",
                    )
                    source?.let { Converters.sourceToString(it) }
                } catch (e: Exception) {
                    log.debug("resolve: id={} provider-chain lookup threw: {}", candidate, e.toString())
                    null
                }
            }
        if (providerChainContent != null) {
            log.debug("resolve: id={} resolved via provider chain, length={}", id, providerChainContent.length)
            return providerChainContent
        }

        return try {
            val bundled =
                candidateIds.firstNotNullOfOrNull { candidate ->
                    FhirLibrarySourceProvider().getLibrarySource(candidate)
                }?.let { Converters.sourceToString(it) }
            log.debug(
                "resolve: id={} direct FhirLibrarySourceProvider returned {}",
                id,
                if (bundled != null) "content, length=${bundled.length}" else "null",
            )
            bundled
        } catch (e: Exception) {
            log.debug("resolve: id={} direct FhirLibrarySourceProvider threw: {}", id, e.toString())
            null
        }
    }
}
