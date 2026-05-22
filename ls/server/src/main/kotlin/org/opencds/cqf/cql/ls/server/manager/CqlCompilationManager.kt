package org.opencds.cqf.cql.ls.server.manager

import org.antlr.v4.kotlinruntime.CharStreams
import org.antlr.v4.kotlinruntime.CommonTokenStream
import org.cqframework.cql.cql2elm.CqlCompiler
import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.ModelManager
import org.cqframework.cql.cql2elm.model.Model
import org.cqframework.cql.cql2elm.quick.FhirLibrarySourceProvider
import org.cqframework.cql.gen.cqlLexer
import org.cqframework.cql.gen.cqlParser
import org.fhir.ucum.UcumEssenceService
import org.fhir.ucum.UcumService
import org.hl7.cql.model.ModelIdentifier
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Converters
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.provider.ContentServiceModelInfoProvider
import org.opencds.cqf.cql.ls.server.provider.FederatedLibrarySourceProvider
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

data class CompilationResult(
    val compiler: CqlCompiler,
    val parseTree: cqlParser.LibraryContext,
)

class CqlCompilationManager(
    private val contentService: ContentService,
    private val compilerOptionsManager: CompilerOptionsManager,
    private val igContextManager: IgContextManager,
    private val libraryResolutionManager: LibraryResolutionManager,
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

    // Cache: compiled result + ANTLR parse tree per source URI
    private val compilationCache = ConcurrentHashMap<URI, CompilationResult>()

    // Forward index: URI → what library identifier it compiled to
    private val uriToIdentifier = ConcurrentHashMap<URI, VersionedIdentifier>()

    // Reverse index: library identifier → which URIs depend on it
    private val reverseDeps = ConcurrentHashMap<VersionedIdentifier, MutableSet<URI>>()

    // Protects atomic reads/writes across all three maps
    private val indexLock = ReentrantReadWriteLock()

    fun compile(uri: URI): CqlCompiler? {
        compilationCache[uri]?.let {
            log.debug("compile: cache hit for {}", uri)
            return it.compiler
        }
        val input = contentService.read(uri)
        if (input == null) {
            log.debug("compile: contentService.read() returned null for {}", uri)
            return null
        }
        log.debug("compile: starting fresh compilation for {}", uri)
        return compile(uri, input).compiler
    }

    fun compile(
        uri: URI,
        stream: InputStream,
    ): CompilationResult {
        val cqlText = Converters.inputStreamToString(stream)

        // Phase: ANTLR parse tree (for position resolution)
        val parseTree = parseCql(cqlText)

        // Phase: CQL → ELM compilation
        val modelManager = createModelManager()
        val libraryManager = createLibraryManager(Uris.getHead(uri), modelManager)
        val compiler = CqlCompiler(null, null, libraryManager)
        compiler.run(cqlText)
        log.debug(
            "compile: finished for {}; library={}, exceptions={}",
            uri,
            compiler.library?.identifier?.id,
            compiler.exceptions?.size,
        )
        val result = CompilationResult(compiler, parseTree)
        compilationCache[uri] = result
        updateIndex(uri, compiler)
        return result
    }

    fun getParseTree(uri: URI): cqlParser.LibraryContext? {
        return compilationCache[uri]?.parseTree ?: compile(uri)?.let { getParseTree(uri) }
    }

    private fun parseCql(cqlText: String): cqlParser.LibraryContext {
        val lexer = cqlLexer(CharStreams.fromString(cqlText))
        val tokens = CommonTokenStream(lexer)
        val parser = cqlParser(tokens)
        return parser.library()
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
        // Priority: local files (1) → npm packages (2) → bundled FHIRHelpers (3 — lowest)
        libraryManager.librarySourceLoader.registerProvider(
            FederatedLibrarySourceProvider(root, contentService, igContextManager.getContext(root)),
        )
        igContextManager.setupLibraryManager(root, libraryManager) // registers npm (2)
        // Register other workspace projects' namespaces AFTER npm so ensureNamespaceRegistered
        // is a safe no-op if npm already registered the same namespace.
        libraryResolutionManager.registerWorkspaceNamespaces(libraryManager)
        libraryManager.librarySourceLoader.registerProvider(FhirLibrarySourceProvider()) // (3)
        return libraryManager
    }
}
