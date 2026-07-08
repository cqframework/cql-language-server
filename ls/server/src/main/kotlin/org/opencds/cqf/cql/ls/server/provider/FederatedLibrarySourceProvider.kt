package org.opencds.cqf.cql.ls.server.provider

import kotlinx.io.Source
import kotlinx.io.readString
import org.cqframework.cql.cql2elm.LibrarySourceProvider
import org.cqframework.fhir.npm.ILibraryReader
import org.cqframework.fhir.npm.LibraryLoader
import org.cqframework.fhir.npm.NpmLibrarySourceProvider
import org.cqframework.fhir.utilities.LoggerAdapter
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Converters
import org.opencds.cqf.cql.ls.server.manager.IgPackageContext
import org.opencds.cqf.cql.ls.server.service.NpmLibraryMaterializationCache
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Unified library source provider that enforces the canonical resolution order:
 *
 *  1. Same-project files (within the file's directory tree)
 *  2. Cross-project files (other workspace folders — handled by [ContentService])
 *  3. NPM packages (FHIR IG dependencies from the local package cache)
 *
 * Built-in FHIRHelpers ([org.cqframework.cql.cql2elm.quick.FhirLibrarySourceProvider]) should be
 * registered *after* this provider so it acts as a last-resort fallback.
 *
 * Named after [org.opencds.cqf.cql.ls.server.service.FederatedContentService] — same
 * "merge multiple sources into one" pattern.
 */
class FederatedLibrarySourceProvider(
    private val root: URI,
    private val contentService: ContentService,
    pkgContext: IgPackageContext?,
) : LibrarySourceProvider {
    companion object {
        private val log = LoggerFactory.getLogger(FederatedLibrarySourceProvider::class.java)
    }

    // Build the NPM delegate eagerly at construction time, not per-lookup.
    private val npmProvider: NpmLibrarySourceProvider? =
        if (pkgContext != null) {
            val fhirVersion = pkgContext.igContext.fhirVersion
            val reader: ILibraryReader = LibraryLoader(fhirVersion)
            NpmLibrarySourceProvider(pkgContext.packageManager.npmList, reader, LoggerAdapter(log))
        } else {
            null
        }

    override fun getLibrarySource(libraryIdentifier: VersionedIdentifier): Source? =
        // Tiers 1 + 2: same-project then cross-project (FileContentService.locate() decides scope)
        contentServiceSource(libraryIdentifier)
            // Tier 3: NPM packages
            ?: npmProvider?.getLibrarySource(libraryIdentifier)?.let {
                materializeAndRewrap(libraryIdentifier, it)
            }

    /**
     * Reads an NPM-resolved [Source] fully, best-effort materializes it to disk via
     * [NpmLibraryMaterializationCache] (so LSP navigation and DAP debugging can later resolve a
     * real file for this identifier — see [org.opencds.cqf.cql.ls.server.service.FileContentService]'s
     * namespace fast-path), and returns a fresh [Source] over the same text so the actual
     * compiler-facing behavior is unaffected by having been read once here. A materialization
     * failure never prevents returning valid, compileable content.
     */
    internal fun materializeAndRewrap(
        libraryIdentifier: VersionedIdentifier,
        source: Source,
        cacheRoot: java.io.File = NpmLibraryMaterializationCache.defaultCacheRoot,
    ): Source {
        val cqlText = source.readString()
        try {
            NpmLibraryMaterializationCache.materialize(libraryIdentifier, cqlText, cacheRoot)
        } catch (e: Exception) {
            log.warn("Failed to materialize NPM library '{}': {}", libraryIdentifier.id, e.message)
        }
        return Converters.stringToSource(cqlText)
    }

    private fun contentServiceSource(identifier: VersionedIdentifier): Source? {
        // Use locate() + read(uri) rather than read(root, identifier) to handle the case where
        // multiple workspace folders contain a library with the same name — the default
        // read(root, identifier) throws IllegalStateException when locate() returns > 1 result.
        val locations =
            try {
                contentService.locate(root, identifier)
            } catch (e: Exception) {
                log.warn("Error locating '{}': {}", identifier.id, e.message)
                return null
            }
        if (locations.isEmpty()) return null
        if (locations.size > 1) {
            log.warn(
                "Multiple locations found for '{}' version '{}'; using: {}",
                identifier.id,
                identifier.version,
                locations.first(),
            )
        }
        return try {
            val source = contentService.read(locations.first())?.let { Converters.inputStreamToSource(it) }
            log.debug(
                "contentServiceSource: '{}' → {} ({})",
                identifier.id,
                locations.first(),
                if (source != null) "ok" else "read returned null",
            )
            source
        } catch (e: Exception) {
            log.warn("Error reading '{}' from {}: {}", identifier.id, locations.first(), e.message)
            null
        }
    }
}
