package org.opencds.cqf.cql.ls.server.manager

import org.cqframework.cql.cql2elm.CqlCompiler
import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.ModelManager
import org.cqframework.cql.cql2elm.model.Model
import org.cqframework.cql.cql2elm.quick.FhirLibrarySourceProvider
import org.fhir.ucum.UcumEssenceService
import org.fhir.ucum.UcumService
import org.hl7.cql.model.ModelIdentifier
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Converters
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.provider.ContentServiceModelInfoProvider
import org.opencds.cqf.cql.ls.server.provider.ContentServiceSourceProvider
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

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

    // Cache: compiled result per source URI
    private val compilationCache = ConcurrentHashMap<URI, CqlCompiler>()

    // Forward index: URI → what library identifier it compiled to
    private val uriToIdentifier = ConcurrentHashMap<URI, VersionedIdentifier>()

    // Reverse index: library identifier → which URIs depend on it
    private val reverseDeps = ConcurrentHashMap<VersionedIdentifier, MutableSet<URI>>()

    // Protects atomic reads/writes across all three maps
    private val indexLock = ReentrantReadWriteLock()

    fun compile(uri: URI): CqlCompiler? {
        compilationCache[uri]?.let { return it }
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
        compilationCache[uri] = compiler
        updateIndex(uri, compiler)
        return compiler
    }

    fun invalidate(uri: URI) {
        indexLock.writeLock().lock()
        try {
            compilationCache.remove(uri)
            val id = uriToIdentifier[uri]
            if (id != null) {
                reverseDeps[id]?.forEach { compilationCache.remove(it) }
            }
        } finally {
            indexLock.writeLock().unlock()
        }
    }

    fun getDependentUris(identifier: VersionedIdentifier): Set<URI> {
        indexLock.readLock().lock()
        return try {
            reverseDeps[identifier]?.toSet() ?: emptySet()
        } finally {
            indexLock.readLock().unlock()
        }
    }

    private fun updateIndex(
        uri: URI,
        compiler: CqlCompiler,
    ) {
        val library = compiler.compiledLibrary?.library ?: return
        val identifier = library.identifier ?: return
        indexLock.writeLock().lock()
        try {
            val oldId = uriToIdentifier[uri]
            if (oldId != null) reverseDeps[oldId]?.remove(uri)
            uriToIdentifier[uri] = identifier
            library.includes?.def?.forEach { includeDef ->
                val depId =
                    VersionedIdentifier()
                        .withId(includeDef.path)
                        .withVersion(includeDef.version)
                reverseDeps.getOrPut(depId) { ConcurrentHashMap.newKeySet() }.add(uri)
            }
        } finally {
            indexLock.writeLock().unlock()
        }
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
        igContextManager.setupLibraryManager(root, libraryManager)
        return libraryManager
    }
}
