package org.opencds.cqf.cql.ls.server.provider

import org.hl7.cql.model.ModelIdentifier
import org.hl7.cql.model.ModelInfoProvider
import org.hl7.elm_modelinfo.r1.ModelInfo
import org.hl7.elm_modelinfo.r1.serializing.parseModelInfoXml
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Converters
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class ContentServiceModelInfoProvider(
    private val root: URI,
    private val contentService: ContentService,
) : ModelInfoProvider {
    companion object {
        private val log = LoggerFactory.getLogger(ContentServiceModelInfoProvider::class.java)

        /**
         * Renders a ModelInfo's declared dependencies (`requiredModelInfo`) as
         * `[name version, ...]`. Logged after a successful parse so version conflicts are visible:
         * e.g. a model that requires `USCore 7.0.0` while the content loads `USCore 6.1.0-derived`
         * surfaces as a mismatch in the log. Returns `[]` when there are no declared dependencies.
         */
        internal fun formatRequiredModels(modelInfo: ModelInfo): String =
            modelInfo.requiredModelInfo
                .joinToString(prefix = "[", postfix = "]") { req ->
                    "${req.name}${req.version?.let { " $it" } ?: ""}"
                }

        /**
         * Tracks the versions each model has been seen at, per provider root: `(rootKey, modelId)`
         * → `version` → `source`. Populated as models are requested and as their declared
         * dependencies (`requiredModelInfo`) are parsed. The CQL compiler allows exactly one
         * version of a model per translation, so seeing a model at two versions under the same
         * root is a genuine conflict (e.g. the content loads `USCore 6.1.0-derived` while a
         * developer-supplied C4BB ModelInfo requires `USCore 7.0.0`). Keyed by root so unrelated
         * projects in the same workspace do not cross-contaminate.
         */
        private val observedModelVersions =
            ConcurrentHashMap<Pair<String, String>, ConcurrentHashMap<String, String>>()

        /**
         * Records that [modelId] was seen at [version] (attributed to [source]) under [rootKey],
         * and returns a human-readable conflict description (`v1 (src1), v2 (src2)`) when that
         * model is now known at more than one version under the same root — i.e. an actual model
         * version conflict. Returns null when there is no conflict or [version] is null/blank.
         */
        internal fun recordVersionAndDetectConflict(
            rootKey: String,
            modelId: String,
            version: String?,
            source: String,
        ): String? {
            if (version.isNullOrBlank()) return null
            val versions = observedModelVersions.getOrPut(rootKey to modelId) { ConcurrentHashMap() }
            versions.putIfAbsent(version, source)
            return if (versions.size > 1) {
                versions.entries.joinToString(", ") { "${it.key} (${it.value})" }
            } else {
                null
            }
        }

        /** Clears the cross-request version tracking. Intended for tests. */
        internal fun clearObservedVersions() = observedModelVersions.clear()
    }

    override fun load(modelIdentifier: ModelIdentifier): ModelInfo? {
        val modelName = modelIdentifier.id
        val modelVersion = modelIdentifier.version

        log.info(
            "ContentServiceModelInfoProvider: resolving model '{}' version '{}' (root={})",
            modelName,
            modelVersion,
            root,
        )

        // Record the requested version; warn if this model is now known at two versions.
        recordVersionAndDetectConflict(root.toString(), modelName, modelVersion, "requested")?.let {
            log.warn("ContentServiceModelInfoProvider: model version conflict for '{}': {}", modelName, it)
        }

        return try {
            val modelUri =
                Uris.addPath(
                    root,
                    "/${modelName.lowercase()}-modelinfo${modelVersion?.let { "-$it" } ?: ""}.xml",
                ) ?: run {
                    log.info(
                        "ContentServiceModelInfoProvider: could not build model info URI for '{}' from root {}",
                        modelName,
                        root,
                    )
                    return null
                }
            log.info("ContentServiceModelInfoProvider: attempting to read model info for '{}' from {}", modelName, modelUri)
            val modelInputStream =
                contentService.read(modelUri) ?: run {
                    log.info("ContentServiceModelInfoProvider: NOT FOUND — no content at {}", modelUri)
                    return null
                }
            val modelInfo = parseModelInfoXml(Converters.inputStreamToString(modelInputStream))
            log.info("ContentServiceModelInfoProvider: FOUND and parsed model info for '{}' at {}", modelName, modelUri)
            log.warn(
                "ContentServiceModelInfoProvider: '{}' requires {}",
                modelName,
                formatRequiredModels(modelInfo),
            )
            // Record each declared dependency's version; warn on any actual version conflict
            // (the same model now known at two versions under this root).
            for (req in modelInfo.requiredModelInfo) {
                val depName = req.name ?: continue
                recordVersionAndDetectConflict(root.toString(), depName, req.version, "required by $modelName")?.let {
                    log.warn("ContentServiceModelInfoProvider: model version conflict for '{}': {}", depName, it)
                }
            }
            modelInfo
        } catch (e: Exception) {
            log.error("ContentServiceModelInfoProvider: error loading model info for '{}' from root {}", modelName, root, e)
            throw IllegalArgumentException("Could not load definition for model info ${modelIdentifier.id}.", e)
        }
    }
}
