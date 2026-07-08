package org.opencds.cqf.cql.ls.server.manager

import org.cqframework.fhir.npm.NpmPackageManager
import org.cqframework.fhir.utilities.IGContext
import org.hl7.cql.model.NamespaceInfo

/**
 * The resolved package context for a workspace root: the parsed [IGContext] plus a
 * [NpmPackageManager] whose npmList was seeded with in-memory packages for `version="dev"`
 * (sibling project) dependencies — the loader itself only sees the non-dev dependencies, so
 * it never consults `~/.fhir/packages` for dev packages.
 *
 * Replaces `org.cqframework.fhir.npm.NpmProcessor`, which hardcodes an unseeded
 * `NpmPackageManager` in its constructor and therefore always throws when a dev dependency
 * is not present in the local FHIR package cache.
 *
 * @param devStamps dev project root dir → source stamp ([IgContextManager.computeSourceStamp])
 *   at the time the seeded package was built; used to detect stale contexts.
 */
class IgPackageContext(
    val igContext: IGContext,
    val packageManager: NpmPackageManager,
    val devStamps: Map<String, Long>,
) {
    val igNamespace: NamespaceInfo?
        get() {
            val packageId = igContext.packageId ?: return null
            val canonical = igContext.canonicalBase ?: return null
            return NamespaceInfo(packageId, canonical)
        }

    val namespaces: List<NamespaceInfo>
        get() =
            packageManager.npmList.mapNotNull { p ->
                val name = p.name()
                val canonical = p.canonical()
                if (!name.isNullOrBlank() && !canonical.isNullOrBlank()) {
                    NamespaceInfo(name, canonical)
                } else {
                    null
                }
            }
}
