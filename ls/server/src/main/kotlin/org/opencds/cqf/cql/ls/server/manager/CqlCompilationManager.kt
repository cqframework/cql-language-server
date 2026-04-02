package org.opencds.cqf.cql.ls.server.manager

import org.cqframework.cql.cql2elm.CqlCompiler
import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.ModelManager
import org.cqframework.cql.cql2elm.model.Model
import org.cqframework.cql.cql2elm.quick.FhirLibrarySourceProvider
import org.fhir.ucum.UcumEssenceService
import org.fhir.ucum.UcumService
import org.hl7.cql.model.ModelIdentifier
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Converters
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.provider.ContentServiceModelInfoProvider
import org.opencds.cqf.cql.ls.server.provider.ContentServiceSourceProvider
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.net.URI

class CqlCompilationManager(
    private val contentService: ContentService,
    private val compilerOptionsManager: CompilerOptionsManager,
    private val igContextManager: IgContextManager,
) {
    companion object {
        private val log = LoggerFactory.getLogger(CqlCompilationManager::class.java)
        private var ucumService: UcumService? = null

        init {
            try {
                ucumService =
                    UcumEssenceService(
                        UcumEssenceService::class.java.getResourceAsStream("/ucum-essence.xml"),
                    )
            } catch (e: Exception) {
                log.warn("error initializing UcumService", e)
            }
        }
    }

    private val globalCache = HashMap<ModelIdentifier, Model>()

    private fun getIgContextManager(): IgContextManager = igContextManager

    fun compile(uri: URI): CqlCompiler? {
        val input = contentService.read(uri) ?: return null
        return compile(uri, input)
    }

    fun compile(
        uri: URI,
        stream: InputStream,
    ): CqlCompiler {
        val modelManager = createModelManager()
        val libraryManager = createLibraryManager(Uris.getHead(uri), modelManager)
        val compiler = CqlCompiler(null, null, libraryManager)
        compiler.run(Converters.inputStreamToString(stream))
        return compiler
    }

    private fun createModelManager() = ModelManager(globalCache)

    private fun createLibraryManager(
        root: URI,
        modelManager: ModelManager,
    ): LibraryManager {
        modelManager.modelInfoLoader.registerModelInfoProvider(
            ContentServiceModelInfoProvider(root, contentService),
        )
        val libraryManager = LibraryManager(modelManager, compilerOptionsManager.getOptions(root))
        libraryManager.librarySourceLoader.registerProvider(
            ContentServiceSourceProvider(root, contentService),
        )
        libraryManager.librarySourceLoader.registerProvider(FhirLibrarySourceProvider())
        getIgContextManager().setupLibraryManager(root, libraryManager)
        return libraryManager
    }
}
