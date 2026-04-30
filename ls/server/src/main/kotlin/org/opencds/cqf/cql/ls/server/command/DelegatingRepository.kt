package org.opencds.cqf.cql.ls.server.command

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.model.api.IQueryParameterType
import ca.uhn.fhir.repository.IRepository
import ca.uhn.fhir.rest.api.MethodOutcome
import com.google.common.collect.Multimap
import org.hl7.fhir.instance.model.api.IBaseBundle
import org.hl7.fhir.instance.model.api.IBaseParameters
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.instance.model.api.IIdType

/**
 * Mutable [IRepository] wrapper. The engine is created once holding this wrapper; before each
 * patient's [evaluate][org.opencds.cqf.fhir.cql.CqlEngine.evaluate] call, [current] is
 * reassigned to that patient's repository. This allows a single engine — and its cached
 * [LibraryManager][org.cqframework.cql.cql2elm.LibraryManager] — to serve all patients in a
 * batch without recompiling CQL on every iteration.
 */
class DelegatingRepository(initial: IRepository) : IRepository {
    var current: IRepository = initial

    override fun fhirContext(): FhirContext = current.fhirContext()

    override fun <T : IBaseResource, I : IIdType> read(
        aClass: Class<T>,
        i: I,
        map: Map<String, String>,
    ): T = current.read(aClass, i, map)

    override fun <T : IBaseResource> create(
        t: T,
        map: Map<String, String>,
    ): MethodOutcome = current.create(t, map)

    override fun <T : IBaseResource> update(
        t: T,
        map: Map<String, String>,
    ): MethodOutcome = current.update(t, map)

    override fun <T : IBaseResource, I : IIdType> delete(
        aClass: Class<T>,
        i: I,
        map: Map<String, String>,
    ): MethodOutcome = current.delete(aClass, i, map)

    override fun <B : IBaseBundle, T : IBaseResource> search(
        aClass: Class<B>,
        aClass1: Class<T>,
        multimap: Multimap<String, List<IQueryParameterType>>,
        map: Map<String, String>,
    ): B = current.search(aClass, aClass1, multimap, map)

    override fun <R : IBaseResource, P : IBaseParameters, T : IBaseResource> invoke(
        aClass: Class<T>,
        s: String,
        p: P,
        aClass1: Class<R>,
        map: Map<String, String>,
    ): R = current.invoke(aClass, s, p, aClass1, map)

    override fun <R : IBaseResource, P : IBaseParameters, I : IIdType> invoke(
        i: I,
        s: String,
        p: P,
        aClass: Class<R>,
        map: Map<String, String>,
    ): R = current.invoke(i, s, p, aClass, map)
}
