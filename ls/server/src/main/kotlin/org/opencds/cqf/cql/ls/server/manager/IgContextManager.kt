package org.opencds.cqf.cql.ls.server.manager

import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.fhir.npm.ILibraryReader
import org.cqframework.fhir.npm.NpmLibrarySourceProvider
import org.cqframework.fhir.npm.NpmModelInfoProvider
import org.cqframework.fhir.npm.NpmProcessor
import org.cqframework.fhir.utilities.IGContext
import org.cqframework.fhir.utilities.LoggerAdapter
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Paths
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

class IgContextManager(private val contentService: ContentService) {
    companion object {
        private val log = LoggerFactory.getLogger(IgContextManager::class.java)
    }

    private val cachedContext = ConcurrentHashMap<URI, Optional<NpmProcessor>>()

    fun getContext(uri: URI): NpmProcessor? {
        val root = Uris.getHead(uri)
        return cachedContext.getOrPut(root) { readContext(root) }.orElse(null)
    }

    protected fun clearContext(uri: URI) {
        val root = Uris.getHead(uri)
        cachedContext.remove(root)
    }

    protected fun readContext(rootUri: URI): Optional<NpmProcessor> {
        val igContext = findIgContext(rootUri)
        return if (igContext != null) Optional.of(NpmProcessor(igContext)) else Optional.empty()
    }

    @Synchronized
    fun setupLibraryManager(
        uri: URI,
        libraryManager: LibraryManager,
    ) {
        val npmProcessor = getContext(uri) ?: return
        val namespaceManager = libraryManager.namespaceManager
        npmProcessor.igNamespace?.let { namespaceManager.ensureNamespaceRegistered(it) }
        val fhirVersion = npmProcessor.igContext?.fhirVersion ?: return
        val reader: ILibraryReader = org.cqframework.fhir.npm.LibraryLoader(fhirVersion)
        val adapter = LoggerAdapter(log)
        val npmList = npmProcessor.getPackageManager().npmList
        libraryManager.librarySourceLoader.registerProvider(
            NpmLibrarySourceProvider(npmList, reader, adapter),
        )
        libraryManager.modelManager.modelInfoLoader.registerModelInfoProvider(
            NpmModelInfoProvider(npmList, reader, adapter),
        )

        val keys = mutableSetOf<String>()
        val uris = mutableSetOf<String>()
        for (n in npmProcessor.namespaces) {
            if (!keys.contains(n.name) && !uris.contains(n.uri)) {
                libraryManager.namespaceManager.addNamespace(n)
                keys.add(n.name)
                uris.add(n.uri)
            }
        }
    }

    protected fun findIgContext(uri: URI): IGContext? {
        log.info("Searching for ini file in {}", uri)
        var current = uri
        for (i in 0 until 2) {
            val parent = Uris.getHead(current)
            if (parent != current) {
                current = parent
                val igIniPath = Uris.addPath(parent, "/ig.ini") ?: continue
                log.info("Attempting to read ini from path {}", igIniPath)
                val input = contentService.read(igIniPath)
                if (input != null) {
                    log.info("Initializing ig from ini...")
                    val igContext = IGContext(LoggerAdapter(log))
                    igContext.initializeFromIni(Paths.get(igIniPath).toString())
                    log.info("IGContext Initialized.")
                    return igContext
                }
            }
        }
        return null
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onMessageEvent(event: DidChangeWatchedFilesEvent) {
        for (e in event.params().changes) {
            if (e.uri.endsWith("ig.ini")) {
                Uris.parseOrNull(e.uri)?.let { clearContext(it) }
            }
        }
    }
}
