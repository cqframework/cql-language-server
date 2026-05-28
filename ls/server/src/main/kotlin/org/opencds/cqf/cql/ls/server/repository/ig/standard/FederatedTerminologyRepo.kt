package org.opencds.cqf.cql.ls.server.repository.ig.standard

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.model.api.IQueryParameterType
import ca.uhn.fhir.repository.IRepository
import ca.uhn.fhir.rest.api.MethodOutcome
import ca.uhn.fhir.util.BundleUtil
import com.google.common.collect.Multimap
import org.hl7.fhir.instance.model.api.IBaseBundle
import org.hl7.fhir.instance.model.api.IBaseParameters
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.instance.model.api.IIdType
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Terminology repository that federates ValueSet (and other terminology resource) lookups
 * across multiple workspace projects.
 *
 * During Execute CQL, a primary library may import helper libraries from other workspace
 * projects. Those helpers reference ValueSets stored in *their own* project's
 * `input/vocabulary/` directory rather than the primary project's. This class tries each
 * project's [IgStandardRepository] in insertion order and returns the first non-empty result.
 *
 * Named after [org.opencds.cqf.cql.ls.server.provider.FederatedLibrarySourceProvider] —
 * same "merge multiple sources into one" pattern.
 *
 * @param fhirContext FHIR version context shared across all delegates.
 * @param inputPaths  List of `input/` directory paths, one per workspace project.
 *                    Each is used as the root for an [IgStandardRepository], which will
 *                    resolve `input/vocabulary/valueset/` automatically via convention detection.
 */
class FederatedTerminologyRepo(
    private val fhirContext: FhirContext,
    inputPaths: List<Path>,
) : IRepository {
    companion object {
        private val log = LoggerFactory.getLogger(FederatedTerminologyRepo::class.java)
    }

    internal val delegates: List<IRepository> =
        inputPaths.mapNotNull { path ->
            try {
                IgStandardRepository(fhirContext, path)
            } catch (e: Exception) {
                log.warn("Could not create terminology repository for {}: {}", path, e.message)
                null
            }
        }

    override fun fhirContext(): FhirContext = fhirContext

    /**
     * Searches each delegate in order and returns the first bundle that contains at least
     * one resource entry. Returns an empty bundle from the first delegate when none match.
     */
    override fun <B : IBaseBundle, T : IBaseResource> search(
        bundleType: Class<B>,
        resourceType: Class<T>,
        searchParameters: Multimap<String, List<IQueryParameterType>>,
        headers: Map<String, String>?,
    ): B {
        for (delegate in delegates) {
            val result = delegate.search(bundleType, resourceType, searchParameters, headers)
            if (BundleUtil.toListOfResources(fhirContext, result).isNotEmpty()) {
                return result
            }
        }
        // No project had a match — return the empty bundle from the first delegate,
        // or build a minimal empty bundle ourselves if there are no delegates.
        return delegates.firstOrNull()
            ?.search(bundleType, resourceType, searchParameters, headers)
            ?: bundleType.getDeclaredConstructor().newInstance()
    }

    /**
     * Reads from each delegate in order, returning the first non-null result.
     * Swallows [ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException] so that
     * the next delegate is tried.
     */
    override fun <T : IBaseResource, I : IIdType> read(
        resourceType: Class<T>,
        id: I,
        headers: Map<String, String>?,
    ): T {
        for (delegate in delegates) {
            try {
                return delegate.read(resourceType, id, headers)
            } catch (e: Exception) {
                // ResourceNotFoundException or similar — try next delegate
            }
        }
        throw ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException(id)
    }

    // Write operations are not supported for a read-only terminology federation.
    override fun <T : IBaseResource> create(
        resource: T,
        headers: Map<String, String>?,
    ): MethodOutcome =
        throw UnsupportedOperationException("FederatedTerminologyRepo is read-only")

    override fun <T : IBaseResource> update(
        resource: T,
        headers: Map<String, String>?,
    ): MethodOutcome =
        throw UnsupportedOperationException("FederatedTerminologyRepo is read-only")

    override fun <T : IBaseResource, I : IIdType> delete(
        resourceType: Class<T>,
        id: I,
        headers: Map<String, String>?,
    ): MethodOutcome = throw UnsupportedOperationException("FederatedTerminologyRepo is read-only")

    override fun <R : IBaseResource, P : IBaseParameters, T : IBaseResource> invoke(
        resourceType: Class<T>,
        name: String,
        parameters: P,
        returnType: Class<R>,
        headers: Map<String, String>?,
    ): R = throw UnsupportedOperationException("FederatedTerminologyRepo does not support invoke")

    override fun <R : IBaseResource, P : IBaseParameters, I : IIdType> invoke(
        id: I,
        name: String,
        parameters: P,
        returnType: Class<R>,
        headers: Map<String, String>?,
    ): R = throw UnsupportedOperationException("FederatedTerminologyRepo does not support invoke")
}
