package org.opencds.cqf.cql.ls.server.utility

import org.hl7.elm.r1.IncludeDef
import org.hl7.elm.r1.VersionedIdentifier

object ElmIdentifiers {
    /**
     * Builds a [VersionedIdentifier] from an [IncludeDef], correctly splitting
     * namespace-qualified ELM paths into [VersionedIdentifier.system] and [VersionedIdentifier.id].
     *
     * For namespace-qualified includes, the compiler stores [IncludeDef.path] as a canonical URL
     * (e.g. `"http://smiledigitalhealth.com/CreateCaseFeature"`). Passing that URL verbatim as
     * [VersionedIdentifier.id] with no [VersionedIdentifier.system] causes [ContentService.locate]
     * to fall into an unqualified BFS search that never matches. Splitting into system + local name
     * enables the namespace fast-path in [FileContentService.locate].
     *
     * For unqualified includes (plain local name, no URL), [system] is left null and the
     * unqualified tiered search applies as normal.
     *
     * Returns null when [IncludeDef.path] is null.
     */
    fun fromIncludeDef(includeDef: IncludeDef): VersionedIdentifier? {
        val path = includeDef.path ?: return null
        val vi = VersionedIdentifier()
        if (path.startsWith("http://") || path.startsWith("https://")) {
            val slash = path.lastIndexOf('/')
            if (slash > 0) {
                vi.system = path.substring(0, slash)
                vi.id = path.substring(slash + 1)
            } else {
                vi.id = path
            }
        } else {
            vi.id = path
        }
        vi.version = includeDef.version
        // id must be non-null for callers that use it as a map key
        return if (vi.id != null) vi else null
    }
}
