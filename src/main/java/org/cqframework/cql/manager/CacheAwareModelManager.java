package org.cqframework.cql.manager;

import org.cqframework.cql.cql2elm.ModelInfoLoader;
import org.cqframework.cql.cql2elm.ModelInfoProvider;
import org.cqframework.cql.cql2elm.ModelManager;
import org.cqframework.cql.cql2elm.model.Model;
import org.cqframework.cql.cql2elm.model.SystemModel;
import org.hl7.elm.r1.VersionedIdentifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by Bryn on 12/29/2016.
 */
public class CacheAwareModelManager extends ModelManager {
    private final Map<String, Model> models = new HashMap<>();

    private Map<VersionedIdentifier, Model> globalCache = new HashMap<>();

    public CacheAwareModelManager(Map<VersionedIdentifier, Model> globalCache) {
        this.globalCache = globalCache;
    }

	private Model buildModel(VersionedIdentifier identifier) {
        Model model = null;
        try {
            ModelInfoProvider provider = ModelInfoLoader.getModelInfoProvider(identifier);
            if (identifier.getId().equals("System")) {
                model = new SystemModel(provider.load());
            }
            else {
                model = new Model(provider.load(), resolveModel("System"));
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(String.format("Could not load model information for model %s, version %s.",
                    identifier.getId(), identifier.getVersion()));
        }

        return model;
    }

    @Override
    public Model resolveModel(VersionedIdentifier modelIdentifier) {
        // First check global cache which can contain multiple versions
        Model model = globalCache.get(modelIdentifier);

        // Next check local cache, which can contain only one version.
        if (model == null) {
           model = models.get(modelIdentifier.getId());
        }

        if (model == null) {
            model = buildModel(modelIdentifier);
            globalCache.put(modelIdentifier, model);
            models.put(modelIdentifier.getId(), model);
        }

        if (modelIdentifier.getVersion() != null && !modelIdentifier.getVersion().equals(model.getModelInfo().getVersion())) {
            throw new IllegalArgumentException(String.format("Could not load model information for model %s, version %s because version %s is already loaded.",
                    modelIdentifier.getId(), modelIdentifier.getVersion(), model.getModelInfo().getVersion()));
        }

        return model;
    }
}
