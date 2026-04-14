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
    }

    override fun load(modelIdentifier: ModelIdentifier): ModelInfo? {
        val modelName = modelIdentifier.id
        val modelVersion = modelIdentifier.version

        return try {
            val modelUri =
                Uris.addPath(
                    root,
                    "/${modelName.lowercase()}-modelinfo${modelVersion?.let { "-$it" } ?: ""}.xml",
                ) ?: return null
            val modelInputStream = contentService.read(modelUri) ?: return null
            parseModelInfoXml(Converters.inputStreamToString(modelInputStream))
        } catch (e: Exception) {
            throw IllegalArgumentException("Could not load definition for model info ${modelIdentifier.id}.", e)
        }
    }
}
