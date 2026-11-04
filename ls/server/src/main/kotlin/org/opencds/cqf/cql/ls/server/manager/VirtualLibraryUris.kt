package org.opencds.cqf.cql.ls.server.manager

import org.cqframework.cql.cql2elm.LibraryManager
import org.hl7.elm.r1.VersionedIdentifier
import java.net.URI

/**
 * Utilities for creating, parsing, and resolving virtual library URIs.
 *
 * Virtual URIs use the scheme `cql-virtual` and represent CQL libraries that have
 * no workspace file (e.g. bundled FHIRHelpers or npm-installed IG libraries).
 */
object VirtualLibraryUris {
    const val SCHEME = "cql-virtual"

    /**
     * Creates the virtual URI for a library identifier: `cql-virtual:///<bareId>-<version>.cql`,
     * where a namespace-qualified id is reduced to its last segment and a missing version
     * becomes `unversioned`. Returns null when the id is null or blank.
     */
    fun of(identifier: VersionedIdentifier): URI? {
        val id = identifier.id ?: return null
        if (id.isBlank()) return null
        val bareId = id.substringAfterLast('/')
        val version = identifier.version ?: "unversioned"
        return URI.create("$SCHEME:///$bareId-$version.cql")
    }

    fun isVirtual(uri: URI?): Boolean = uri?.scheme == SCHEME

    // Same heuristic VS Code uses to tell a URI from a filesystem path in DAP `Source.path`.
    // The scheme must be 2+ characters, so a Windows drive letter (`C:\...`, `C:/...`) never matches.
    private val URI_LIKE = Regex("^[a-zA-Z][a-zA-Z0-9+.-]+:")

    fun looksLikeUri(s: String?): Boolean = s != null && URI_LIKE.containsMatchIn(s)

    /**
     * Parses a virtual URI string, accepting both `cql-virtual:///X.cql` and `cql-virtual:/X.cql`
     * (VS Code's `Uri.toString()` drops the empty authority), and returns the canonical `///` form.
     * Returns null for anything that is not a `cql-virtual` URI.
     */
    fun parse(s: String?): URI? {
        val trimmed = s?.trim() ?: return null
        if (!looksLikeUri(trimmed)) return null
        return try {
            val parsed = URI.create(trimmed)
            if (parsed.scheme != SCHEME) return null
            val path = parsed.path ?: return null
            URI.create("$SCHEME://$path")
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Ensures [id] is compiled into [cm] under its virtual URI and returns that URI. A cached
     * entry is reused as-is; otherwise the source is resolved through [lm]'s provider chain
     * (see [LibrarySourceResolver]). Returns null if the source cannot be resolved or compiled.
     */
    fun ensureCompiled(
        cm: CqlCompilationManager,
        lm: LibraryManager?,
        id: VersionedIdentifier,
    ): URI? {
        val uri = of(id) ?: return null
        if (cm.getSourceText(uri) != null) return uri
        val text = LibrarySourceResolver.resolve(lm, id) ?: return null
        return try {
            cm.compile(uri, text.byteInputStream())
            uri
        } catch (_: Exception) {
            null
        }
    }
}
