package org.opencds.cqf.cql.ls.server.manager;

import java.util.HashMap;
import java.util.Map;
import org.cqframework.cql.cql2elm.ModelInfoLoader;
import org.cqframework.cql.cql2elm.ModelManager;
import org.cqframework.cql.cql2elm.model.Model;
import org.cqframework.cql.cql2elm.model.SystemModel;
import org.hl7.cql.model.ModelIdentifier;
import org.hl7.elm.r1.VersionedIdentifier;
import org.hl7.elm_modelinfo.r1.ModelInfo;

/**
 * Created by Bryn on 12/29/2016.
 */
public class CacheAwareModelManager extends ModelManager {

    private final Map<ModelIdentifier, Model> globalCache;

    private final Map<String, Model> localCache;

    public CacheAwareModelManager(Map<ModelIdentifier, Model> globalCache) {
        this.globalCache = globalCache;
        this.localCache = new HashMap<>();
    }

    private VersionedIdentifier toVersionedIdentifier(ModelIdentifier modelIdentifier) {
        return new VersionedIdentifier()
                .withId(modelIdentifier.getId())
                .withVersion(modelIdentifier.getVersion())
                .withSystem(modelIdentifier.getSystem());
    }

    private ModelIdentifier toModelIdentifier(VersionedIdentifier versionedIdentifier) {
        return new ModelIdentifier()
                .withId(versionedIdentifier.getId())
                .withVersion(versionedIdentifier.getVersion())
                .withSystem(versionedIdentifier.getSystem());
    }

    private Model buildModel(ModelIdentifier identifier) {
        ModelInfoLoader loader = new ModelInfoLoader();
        Model model = null;
        try {
            ModelInfo modelInfo = loader.getModelInfo(identifier);
            if (identifier.getId().equals("System")) {
                model = new SystemModel(modelInfo);
            } else {
                model = new Model(modelInfo, this);
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(
                    String.format("Could not load model information for model %s, version %s.",
                            identifier.getId(), identifier.getVersion()));
        }

        return model;
    }

    @Override
    public Model resolveModel(ModelIdentifier modelIdentifier) {
        Model model = null;
        if (this.localCache.containsKey(modelIdentifier.getId())) {
            model = this.localCache.get(modelIdentifier.getId());
            if (modelIdentifier.getVersion() != null
                    && !modelIdentifier.getVersion().equals(model.getModelInfo().getVersion())) {
                throw new IllegalArgumentException(String.format(
                        "Could not load model information for model %s, version %s because version %s is already loaded.",
                        modelIdentifier.getId(), modelIdentifier.getVersion(),
                        model.getModelInfo().getVersion()));
            }

        }

        if (model == null && this.globalCache.containsKey(modelIdentifier)) {
            model = this.globalCache.get(modelIdentifier);
            this.localCache.put(modelIdentifier.getId(), model);
        }

        if (model == null) {
            model = buildModel(modelIdentifier);
            this.globalCache.put(modelIdentifier, model);
            this.localCache.put(modelIdentifier.getId(), model);
        }

        return model;
    }
}
