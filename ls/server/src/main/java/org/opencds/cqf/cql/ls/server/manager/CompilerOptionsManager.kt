package org.opencds.cqf.cql.ls.server.manager

import kotlinx.io.files.Path
import org.cqframework.cql.cql2elm.CqlCompilerOptions
import org.cqframework.cql.cql2elm.CqlTranslatorOptions
import org.cqframework.cql.cql2elm.LibraryBuilder.SignatureLevel
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent
import org.slf4j.LoggerFactory
import java.net.URI

class CompilerOptionsManager(private val contentService: ContentService) {

    companion object {
        private val log = LoggerFactory.getLogger(CompilerOptionsManager::class.java)
    }

    private val cachedOptions = HashMap<URI, CqlCompilerOptions>()

    fun getOptions(uri: URI): CqlCompilerOptions {
        val root = Uris.getHead(uri)
        return cachedOptions.getOrPut(root) { readOptions(root) }
    }

    internal fun clearOptions(uri: URI) {
        val root = Uris.getHead(uri)
        cachedOptions.remove(root)
    }

    protected fun readOptions(rootUri: URI): CqlCompilerOptions {
        var options: CqlCompilerOptions?

        val optionsUri = Uris.addPath(rootUri, "/cql/cql-options.json")
        val input = contentService.read(optionsUri!!)

        options = if (input != null) {
            try {
                CqlTranslatorOptions.fromFile(Path(optionsUri.toURL().path))
                    .cqlCompilerOptions
            } catch (e: Exception) {
                log.info("Exception ${e.message} attempting to load options from $optionsUri, using default options")
                null
            }
        } else {
            log.info("$optionsUri not found, using default options")
            null
        }

        if (options == null) {
            options = CqlTranslatorOptions.defaultOptions().cqlCompilerOptions
        }

        return options!!.withOptions(
            CqlCompilerOptions.Options.EnableLocators,
            CqlCompilerOptions.Options.EnableResultTypes,
            CqlCompilerOptions.Options.EnableAnnotations
        ).withSignatureLevel(SignatureLevel.All)
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onMessageEvent(event: DidChangeWatchedFilesEvent) {
        for (e in event.params().changes) {
            if (e.uri.endsWith("cql-options.json")) {
                clearOptions(Uris.parseOrNull(e.uri)!!)
            }
        }
    }
}
