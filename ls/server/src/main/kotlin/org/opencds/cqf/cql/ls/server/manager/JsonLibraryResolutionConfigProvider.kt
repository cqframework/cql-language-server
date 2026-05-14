package org.opencds.cqf.cql.ls.server.manager

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import org.eclipse.lsp4j.WorkspaceFolder
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

class JsonLibraryResolutionConfigProvider(
    private val workspaceFolders: List<WorkspaceFolder>,
) : LibraryResolutionConfigProvider {
    companion object {
        private val log = LoggerFactory.getLogger(JsonLibraryResolutionConfigProvider::class.java)
        private val jsonMapper = ObjectMapper().configure(JsonParser.Feature.ALLOW_COMMENTS, true)
    }

    private val configCache = ConcurrentHashMap<URI, LibraryResolutionConfig>()

    override fun getConfig(root: URI): LibraryResolutionConfig {
        val folderUri = findContainingFolder(root) ?: return LibraryResolutionConfig()
        return configCache.getOrPut(folderUri) { readConfig(folderUri) }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onMessageEvent(event: DidChangeWatchedFilesEvent) {
        for (e in event.params().changes) {
            if (e.uri.endsWith("config.jsonc") || e.uri.endsWith("config.json")) {
                Uris.parseOrNull(e.uri)?.let { clearConfig(it) }
            }
        }
    }

    private fun clearConfig(uri: URI) {
        val folderUri = findContainingFolder(uri) ?: return
        configCache.remove(folderUri)
    }

    private fun findContainingFolder(uri: URI): URI? {
        for (w in workspaceFolders) {
            val folderUri = Uris.parseOrNull(w.uri) ?: continue
            if (folderUri.relativize(uri) != uri) return folderUri
        }
        return null
    }

    private fun readConfig(folderUri: URI): LibraryResolutionConfig {
        val testsUri = Uris.addPath(folderUri, "input")?.let { Uris.addPath(it, "tests") }
            ?: return LibraryResolutionConfig()
        val configUri = listOf("config.jsonc", "config.json")
            .mapNotNull { Uris.addPath(testsUri, it) }
            .firstOrNull { runCatching { Paths.get(it).toFile().exists() }.getOrDefault(false) }
            ?: return LibraryResolutionConfig()

        return try {
            Paths.get(configUri).toFile().inputStream().use { input ->
                val node = jsonMapper.readTree(input)
                LibraryResolutionConfig(
                    mode = when (node.get("libraryResolution")?.asText()) {
                        "strict" -> LibraryResolutionMode.STRICT
                        else -> LibraryResolutionMode.PATCH_FLEXIBLE
                    },
                    unqualifiedCrossProjectSearch =
                        node.get("unqualifiedCrossProjectSearch")?.asBoolean() ?: false,
                    projectSearchOrder =
                        node.get("projectSearchOrder")?.mapNotNull { it.asText() } ?: emptyList(),
                    projectSearchExclude =
                        node.get("projectSearchExclude")?.mapNotNull { it.asText() }?.toSet()
                            ?: emptySet(),
                )
            }
        } catch (e: Exception) {
            log.warn("Failed to parse config file at {}: {}", configUri, e.message)
            LibraryResolutionConfig()
        }
    }
}
