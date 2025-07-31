package org.opencds.cqf.cql.ls.server.command;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.api.BundleInclusionRule;
import ca.uhn.fhir.model.api.IQueryParameterType;
import ca.uhn.fhir.model.valueset.BundleTypeEnum;
import ca.uhn.fhir.repository.IRepository;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Multimap;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseParameters;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
// TODO: Remove - import org.opencds.cqf.fhir.api.Repository;

public class NoOpRepository implements IRepository {

    private final FhirContext fhirContext;

    public NoOpRepository(FhirContext fhirContext) {
        this.fhirContext = fhirContext;
    }

    @Override
    public <T extends IBaseResource, I extends IIdType> T read(Class<T> aClass, I i, Map<String, String> map) {
        throw new UnsupportedOperationException("Unimplemented method 'read'");
    }

    @Override
    public <T extends IBaseResource> MethodOutcome create(T t, Map<String, String> map) {
        throw new UnsupportedOperationException("Unimplemented method 'create'");
    }

    @Override
    public <T extends IBaseResource> MethodOutcome update(T t, Map<String, String> map) {
        throw new UnsupportedOperationException("Unimplemented method 'update'");
    }

    @Override
    public <T extends IBaseResource, I extends IIdType> MethodOutcome delete(Class<T> aClass, I i, Map<String, String> map) {
        throw new UnsupportedOperationException("Unimplemented method 'delete'");
    }

    @Override
    public <B extends IBaseBundle, T extends IBaseResource> B search(Class<B> aClass, Class<T> aClass1, Multimap<String, List<IQueryParameterType>> multimap, Map<String, String> map) {
        throw new UnsupportedOperationException("Unimplemented method 'search'");
    }

    @Override
    public <R extends IBaseResource, P extends IBaseParameters, T extends IBaseResource> R invoke(Class<T> aClass, String s, P p, Class<R> aClass1, Map<String, String> map) {
        throw new UnsupportedOperationException("Unimplemented method 'invoke'");
    }

    @Override
    public <R extends IBaseResource, P extends IBaseParameters, I extends IIdType> R invoke(I i, String s, P p, Class<R> aClass, Map<String, String> map) {
        throw new UnsupportedOperationException("Unimplemented method 'invoke'");
    }

    @Override
    public FhirContext fhirContext() {
        return this.fhirContext;
    }
}
