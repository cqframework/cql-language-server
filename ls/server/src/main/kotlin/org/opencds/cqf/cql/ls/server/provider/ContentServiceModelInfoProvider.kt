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
    }

    /**
     * Tracks the versions each model has been seen at during this provider's lifetime:
     * `modelId` → `version` → `source`. Populated as models are requested and as their declared
     * dependencies (`requiredModelInfo`) are parsed. The CQL compiler allows exactly one version
     * of a model per translation, so seeing a model at two versions is a genuine conflict (e.g.
     * the content loads `USCore 6.1.0-derived` while a developer-supplied C4BB ModelInfo requires
     * `USCore 7.0.0`).
     *
     * This is instance state, not static: a fresh provider is constructed per compilation
     * (see `CqlCompilationManager.createLibraryManager` and `CqlEvaluator`), so version tracking
     * starts empty each compile and never carries stale versions across runs. Each instance is
     * scoped to a single [root], so keying by `modelId` alone is sufficient.
     */
    private val observedModelVersions = HashMap<String, MutableMap<String, String>>()

    /**
     * Records that [modelId] was seen at [version] (attributed to [source]), and returns a
     * human-readable conflict description when that model is now known at more than one version —
     * i.e. an actual model version conflict. When the observed versions differ *only* by case
     * (e.g. `6.1.0-Derived` vs `6.1.0-derived`), the message calls that out explicitly, since the
     * CQL engine matches model versions with exact, case-sensitive string equality and such a
     * mismatch is a common, easily-missed authoring error. Returns null when there is no conflict
     * or [version] is null/blank.
     */
    internal fun recordVersionAndDetectConflict(
        modelId: String,
        version: String?,
        source: String,
    ): String? {
        if (version.isNullOrBlank()) return null
        val versions = observedModelVersions.getOrPut(modelId) { mutableMapOf() }
        versions.putIfAbsent(version, source)
        if (versions.size <= 1) return null

        val rendered = versions.entries.joinToString(", ") { "${it.key} (${it.value})" }
        val caseOnly = versions.keys.map { it.lowercase() }.distinct().size == 1
        return if (caseOnly) {
            "versions differ only by case — $rendered. The CQL engine matches model versions " +
                "case-sensitively; make them identical."
        } else {
            rendered
        }
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
        recordVersionAndDetectConflict(modelName, modelVersion, "requested")?.let {
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
                recordVersionAndDetectConflict(depName, req.version, "required by $modelName")?.let {
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
