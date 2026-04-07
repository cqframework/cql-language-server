package org.opencds.cqf.cql.ls.server.command

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.api.BundleInclusionRule
import ca.uhn.fhir.model.api.IQueryParameterType
import ca.uhn.fhir.model.valueset.BundleTypeEnum
import ca.uhn.fhir.repository.IRepository
import ca.uhn.fhir.rest.api.MethodOutcome
import com.google.common.collect.Multimap
import org.hl7.fhir.instance.model.api.IBaseBundle
import org.hl7.fhir.instance.model.api.IBaseParameters
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.instance.model.api.IIdType

class NoOpRepository(private val fhirContext: FhirContext) : IRepository {
    override fun <T : IBaseResource, I : IIdType> read(
        aClass: Class<T>,
        i: I,
        map: Map<String, String>,
    ): T {
        throw UnsupportedOperationException("Unimplemented method 'read'")
    }

    override fun <T : IBaseResource> create(
        t: T,
        map: Map<String, String>,
    ): MethodOutcome {
        throw UnsupportedOperationException("Unimplemented method 'create'")
    }

    override fun <T : IBaseResource> update(
        t: T,
        map: Map<String, String>,
    ): MethodOutcome {
        throw UnsupportedOperationException("Unimplemented method 'update'")
    }

    override fun <T : IBaseResource, I : IIdType> delete(
        aClass: Class<T>,
        i: I,
        map: Map<String, String>,
    ): MethodOutcome {
        throw UnsupportedOperationException("Unimplemented method 'delete'")
    }

    @Suppress("UNCHECKED_CAST")
    override fun <B : IBaseBundle, T : IBaseResource> search(
        aClass: Class<B>,
        aClass1: Class<T>,
        multimap: Multimap<String, List<IQueryParameterType>>,
        map: Map<String, String>,
    ): B {
        val factory = fhirContext.newBundleFactory()
        factory.addResourcesToBundle(
            emptyList(),
            BundleTypeEnum.SEARCHSET,
            "",
            BundleInclusionRule.BASED_ON_INCLUDES,
            emptySet(),
        )
        return factory.resourceBundle as B
    }

    override fun <R : IBaseResource, P : IBaseParameters, T : IBaseResource> invoke(
        aClass: Class<T>,
        s: String,
        p: P,
        aClass1: Class<R>,
        map: Map<String, String>,
    ): R {
        throw UnsupportedOperationException("Unimplemented method 'invoke'")
    }

    override fun <R : IBaseResource, P : IBaseParameters, I : IIdType> invoke(
        i: I,
        s: String,
        p: P,
        aClass: Class<R>,
        map: Map<String, String>,
    ): R {
        throw UnsupportedOperationException("Unimplemented method 'invoke'")
    }

    override fun fhirContext(): FhirContext = fhirContext
}
