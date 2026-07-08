package org.opencds.cqf.cql.ls.server.service

import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.ls.server.utility.VersionReader
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Paths

/**
 * Materializes CQL libraries that are bundled on the JVM classpath (currently just FHIRHelpers,
 * served from `/org/hl7/fhir/{id}-{version}.cql` by
 * [org.cqframework.cql.cql2elm.quick.FhirLibrarySourceProvider]) into a real file on disk, so
 * they get a resolvable `file://` URI and Go-to-Definition / Find-References / DAP breakpoints
 * work the same way they do for any workspace file.
 *
 * Resolution is fully on-demand: nothing is extracted until [resolve] is called for an
 * identifier that isn't found anywhere else, and only that one file is written. A workspace that
 * never needs a bundled fallback triggers zero disk I/O from this object.
 *
 * The cache directory is versioned by the running language server's own build version (not a
 * temp directory — it persists across restarts within the same LS version), so it can never go
 * stale: every LS upgrade gets a fresh, empty cache directory.
 */
object BundledLibraryCache {
    private val log = LoggerFactory.getLogger(BundledLibraryCache::class.java)

    private const val RESOURCE_ROOT = "/org/hl7/fhir"
    private const val GENERATED_HEADER =
        "// Auto-generated from clinical_quality_language (quick module)." +
            " Do not edit — changes are not persisted across cql-language-server upgrades.\n"

    private val defaultCacheRoot: File by lazy {
        val lsVersion = VersionReader.loadVersion("cql-ls-server") ?: "unknown"
        Paths.get(
            System.getProperty("user.home"),
            ".cql-language-server",
            "bundled-fhir",
            lsVersion,
        ).toFile()
    }

    /**
     * Returns a file on disk containing [identifier]'s CQL source if it's bundled on the
     * classpath, extracting it into [cacheRoot] on first use. Returns null if no bundled resource
     * matches — mirrors [org.cqframework.cql.cql2elm.quick.FhirLibrarySourceProvider]'s own exact
     * `{id}-{version}` matching (no compatible/patch-flexible fallback for bundled resources).
     */
    fun resolve(
        identifier: VersionedIdentifier,
        cacheRoot: File = defaultCacheRoot,
    ): File? {
        val name = identifier.id ?: return null
        val version = identifier.version ?: return null
        val fileName = "$name-$version.cql"

        val cached = File(cacheRoot, fileName)
        if (cached.exists()) return cached

        val resourcePath = "$RESOURCE_ROOT/$fileName"
        val bytes =
            BundledLibraryCache::class.java.getResourceAsStream(resourcePath)?.use { it.readBytes() }
                ?: return null

        return try {
            cacheRoot.mkdirs()
            cached.writeBytes(GENERATED_HEADER.toByteArray() + bytes)
            cached.setReadOnly()
            log.debug("Materialized bundled library '{}' to {}", fileName, cached)
            cached
        } catch (e: Exception) {
            log.warn("Failed to materialize bundled library '{}': {}", fileName, e.message)
            null
        }
    }
}
