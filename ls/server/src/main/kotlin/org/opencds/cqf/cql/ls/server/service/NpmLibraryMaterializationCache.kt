package org.opencds.cqf.cql.ls.server.service

import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.ls.server.utility.VersionReader
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * Materializes CQL libraries resolved via [org.cqframework.fhir.npm.NpmLibrarySourceProvider]
 * (a FHIR `Library` resource's embedded `text/cql` content, fetched from an installed NPM
 * package under `~/.fhir/packages`) into a real file on disk, as a side effect of a successful
 * compile-time resolution.
 *
 * Unlike [BundledLibraryCache], there's no independent classpath resource to re-derive content
 * from — [materialize] must be called by the code that already resolved the content (
 * [org.opencds.cqf.cql.ls.server.provider.FederatedLibrarySourceProvider]) with the exact bytes
 * it resolved. [lookup] is then a pure, side-effect-free read used by LSP navigation
 * ([org.opencds.cqf.cql.ls.server.service.FileContentService]'s namespace fast-path).
 *
 * This means navigation into a given `(system, id, version)` only works once that library has
 * been successfully compiled at least once in this language server process — an accepted
 * limitation, since opening a file that references it triggers a compile automatically.
 *
 * Never touches `~/.fhir/packages` — this is a separate, LS-owned cache, versioned by the
 * running language server's own build version so it can never go stale across upgrades.
 */
object NpmLibraryMaterializationCache {
    private val log = LoggerFactory.getLogger(NpmLibraryMaterializationCache::class.java)

    private const val GENERATED_HEADER =
        "// Auto-generated from an installed FHIR NPM package's embedded Library resource." +
            " Do not edit — changes are not persisted across cql-language-server upgrades.\n"

    val defaultCacheRoot: File by lazy {
        val lsVersion = VersionReader.loadVersion("cql-ls-server") ?: "unknown"
        Paths.get(
            System.getProperty("user.home"),
            ".cql-language-server",
            "npm-library-cache",
            lsVersion,
        ).toFile()
    }

    /**
     * Writes [cqlText] to a deterministic file for [identifier] if one doesn't already exist.
     * Idempotent — the first successful compile in a session wins; re-materializing the same
     * identifier is a no-op even if the underlying NPM package content were to change without
     * an LS restart (same accepted limitation as [BundledLibraryCache]).
     */
    fun materialize(
        identifier: VersionedIdentifier,
        cqlText: String,
        cacheRoot: File = defaultCacheRoot,
    ): File? {
        val file = targetFile(identifier, cacheRoot) ?: return null
        if (file.exists()) return file

        return try {
            file.parentFile.mkdirs()
            file.writeText(GENERATED_HEADER + cqlText)
            file.setReadOnly()
            log.debug("Materialized NPM-resolved library '{}' to {}", file.name, file)
            file
        } catch (e: Exception) {
            log.warn("Failed to materialize NPM-resolved library '{}': {}", identifier.id, e.message)
            null
        }
    }

    /** Pure lookup — returns the previously materialized file, or null if none exists yet. */
    fun lookup(
        identifier: VersionedIdentifier,
        cacheRoot: File = defaultCacheRoot,
    ): File? = targetFile(identifier, cacheRoot)?.takeIf { it.exists() }

    private fun targetFile(
        identifier: VersionedIdentifier,
        cacheRoot: File,
    ): File? {
        val name = identifier.id ?: return null
        val version = identifier.version ?: return null
        val system = identifier.system ?: return null
        val systemDir = sanitize(system)
        return File(File(cacheRoot, systemDir), "$name-$version.cql")
    }

    /** Filesystem-safe, deterministic slug for a canonical URL (used as a subdirectory name). */
    private fun sanitize(system: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(system.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
