package org.opencds.cqf.cql.ls.server.command;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.api.BundleInclusionRule;
import ca.uhn.fhir.model.api.IQueryParameterType;
import ca.uhn.fhir.model.valueset.BundleTypeEnum;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseParameters;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.opencds.cqf.fhir.api.Repository;

public class NoOpRepository implements Repository {

    private final FhirContext fhirContext;

    public NoOpRepository(FhirContext fhirContext) {
        this.fhirContext = fhirContext;
    }

    @Override
    public <T extends IBaseResource> MethodOutcome create(T arg0, Map<String, String> arg1) {
        throw new UnsupportedOperationException("Unimplemented method 'create'");
    }

    @Override
    public <T extends IBaseResource, I extends IIdType> MethodOutcome delete(
            Class<T> arg0, I arg1, Map<String, String> arg2) {
        throw new UnsupportedOperationException("Unimplemented method 'delete'");
    }

    @Override
    public FhirContext fhirContext() {
        return this.fhirContext;
    }

    @Override
    public <R extends IBaseResource, P extends IBaseParameters, T extends IBaseResource> R invoke(
            Class<T> arg0, String arg1, P arg2, Class<R> arg3, Map<String, String> arg4) {
        throw new UnsupportedOperationException("Unimplemented method 'invoke'");
    }

    @Override
    public <R extends IBaseResource, P extends IBaseParameters, I extends IIdType> R invoke(
            I arg0, String arg1, P arg2, Class<R> arg3, Map<String, String> arg4) {
        throw new UnsupportedOperationException("Unimplemented method 'invoke'");
    }

    @Override
    public <T extends IBaseResource, I extends IIdType> T read(Class<T> arg0, I arg1, Map<String, String> arg2) {
        throw new ResourceNotFoundException(arg1);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <B extends IBaseBundle, T extends IBaseResource> B search(
            Class<B> arg0, Class<T> arg1, Map<String, List<IQueryParameterType>> arg2, Map<String, String> arg3) {
        var factory = this.fhirContext.newBundleFactory();
        factory.addResourcesToBundle(
                Collections.emptyList(), BundleTypeEnum.SEARCHSET, "", BundleInclusionRule.BASED_ON_INCLUDES, Set.of());
        return (B) factory.getResourceBundle();
    }

    @Override
    public <T extends IBaseResource> MethodOutcome update(T arg0, Map<String, String> arg1) {
        throw new UnsupportedOperationException("Unimplemented method 'update'");
    }
}
