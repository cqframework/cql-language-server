package org.opencds.cqf.cql.ls.manager;

import java.util.HashMap;
import java.util.Map;

import org.cqframework.cql.cql2elm.ModelInfoLoader;
import org.cqframework.cql.cql2elm.ModelManager;
import org.cqframework.cql.cql2elm.model.Model;
import org.cqframework.cql.cql2elm.model.SystemModel;
import org.hl7.elm.r1.VersionedIdentifier;
import org.hl7.elm_modelinfo.r1.ModelInfo;

/**
 * Created by Bryn on 12/29/2016.
 */
public class CacheAwareModelManager extends ModelManager {

    private final Map<VersionedIdentifier, Model> globalCache;

    private final Map<String, Model> localCache;

    public CacheAwareModelManager(Map<VersionedIdentifier, Model> globalCache) {
        this.globalCache = globalCache;
        this.localCache = new HashMap<>();
    }

	private Model buildModel(VersionedIdentifier identifier) {
        ModelInfoLoader loader = new ModelInfoLoader();
        Model model = null;
        try {
            ModelInfo modelInfo = loader.getModelInfo(identifier);
            if (identifier.getId().equals("System")) {
                model = new SystemModel(modelInfo);
            }
            else {
                model = new Model(modelInfo, this);
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(String.format("Could not load model information for model {}, version {}.",
                    identifier.getId(), identifier.getVersion()));
        }

        return model;
    }

    @Override
    public Model resolveModel(VersionedIdentifier modelIdentifier) {
        Model model = null;
        if (this.localCache.containsKey(modelIdentifier.getId())) {
            model = this.localCache.get(modelIdentifier.getId());
            if (modelIdentifier.getVersion() != null && !modelIdentifier.getVersion().equals(model.getModelInfo().getVersion())) {
                throw new IllegalArgumentException(String.format("Could not load model information for model {}, version {} because version {} is already loaded.",
                        modelIdentifier.getId(), modelIdentifier.getVersion(), model.getModelInfo().getVersion()));
            }

        }

        // Wasn't in the local cache. Let's check and see if it's valid to load it.
        // if (model == null) {
        //     for (String name : this.localCache.keySet()) {
        //         if (!name.equals("System")) {
        //             throw new IllegalArgumentException(String.format("Could not load model information for model {}, because model {} is already loaded.",
        //                     modelIdentifier.getId(), name));
        //         }
        //     }
        // }

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
