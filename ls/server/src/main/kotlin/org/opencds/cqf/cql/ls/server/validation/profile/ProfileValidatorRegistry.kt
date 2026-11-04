package org.opencds.cqf.cql.ls.server.validation.profile

import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class ProfileEngineState {
    NOT_STARTED,
    WARMING,
    READY,
    FAILED,
}

/** The outcome of asking for a validator: [validator] is non-null only when [state] is READY. */
data class ProfileValidatorLookup(
    val state: ProfileEngineState,
    val validator: ResourceProfileValidator? = null,
    val message: String? = null,
)

/** Hands out profile validators; [ProfileValidatorRegistry] in production, fakes in tests. */
interface ProfileValidatorProvider {
    /** The validator for the IG containing [path]; starts a background build if needed, never blocks. */
    fun acquire(
        path: Path,
        packages: List<String>,
        mode: EngineMode,
    ): ProfileValidatorLookup
}

/** What the registry needs to know about an IG; backed by [IgContextManager] in production. */
interface ProfileIgInfo {
    /** `id#version` of each IG dependency (`ImplementationGuide.dependsOn`). */
    fun dependsOn(igRoot: Path): List<String>

    /** Changes whenever the IG definition changes, invalidating engines built from it. */
    fun generation(igRoot: Path): Long
}

class IgContextManagerIgInfo(private val igContextManager: IgContextManager) : ProfileIgInfo {
    override fun dependsOn(igRoot: Path): List<String> =
        igContextManager.getIgContext(igRoot.resolve("ig.ini").toUri())?.sourceIg?.dependsOn.orEmpty().mapNotNull { dep ->
            val id = dep.packageId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // "dev" dependencies are sibling workspace projects; the cache only holds stubs for them
            val version = dep.version?.takeIf { it.isNotBlank() && it != "dev" } ?: return@mapNotNull null
            "$id#$version"
        }

    // ig.ini's mtime backs up the IgContextManager generation, whose key is the client's ig.ini URI
    // (on Windows that URI's drive-letter form need not match Path.toUri()).
    override fun generation(igRoot: Path): Long {
        val igIni = igRoot.resolve("ig.ini")
        val mtime = runCatching { Files.getLastModifiedTime(igIni).toMillis() }.getOrDefault(0L)
        return igContextManager.generation(igIni.toUri()).toLong() * 31 + mtime
    }
}

/**
 * Caches one core engine per (IG root, configured packages), built in the background on a single
 * dedicated thread. Keeps at most [maxEntries] keys, evicting the least recently used, and rebuilds
 * when the IG's [ProfileIgInfo.generation] changes.
 *
 * One engine serves both QUICK and FULL: a lean QUICK package set gave different results and saved
 * under 20 s of build time (A1 addendum in the spike findings).
 */
class ProfileValidatorRegistry(
    private val igInfo: ProfileIgInfo,
    private val builder: (ProfileEngineSpec) -> ResourceProfileValidator = CoreEngineProfileValidator::build,
    private val executor: ExecutorService = defaultExecutor(),
    private val maxEntries: Int = 2,
) : ProfileValidatorProvider {
    companion object {
        private val log = LoggerFactory.getLogger(ProfileValidatorRegistry::class.java)
        private const val R4_CORE = "hl7.fhir.r4.core"

        private fun defaultExecutor(): ExecutorService =
            Executors.newSingleThreadExecutor { r -> Thread(r, "profile-validator-build").apply { isDaemon = true } }

        /** dependsOn ∪ [extra], one version per package id ([extra] wins), without R4 core. */
        fun mergePackages(
            dependsOn: List<String>,
            extra: List<String>,
        ): List<String> {
            val byId = linkedMapOf<String, String>()
            for (pkg in dependsOn + extra) {
                val id = pkg.substringBefore("#").trim()
                if (id.isEmpty() || id == R4_CORE) continue
                byId[id] = pkg.trim()
            }
            return byId.values.toList()
        }
    }

    data class Key(val igRoot: Path, val packages: List<String>)

    private class Entry(val generation: Long) {
        @Volatile var state = ProfileEngineState.NOT_STARTED

        @Volatile var validator: ResourceProfileValidator? = null

        @Volatile var message: String? = null
    }

    private val entries =
        object : LinkedHashMap<Key, Entry>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>): Boolean =
                (size > maxEntries).also { if (it) log.info("Evicting profile validator for {}", eldest.key.igRoot) }
        }

    /** Starts building the engine for the IG containing [path], without waiting. */
    fun prewarm(
        path: Path,
        packages: List<String>,
    ): ProfileValidatorLookup = acquire(path, packages, EngineMode.QUICK).copy(validator = null)

    override fun acquire(
        path: Path,
        packages: List<String>,
        mode: EngineMode,
    ): ProfileValidatorLookup {
        val igRoot =
            ProfileValidationPaths.findIgRoot(path)
                ?: return ProfileValidatorLookup(ProfileEngineState.FAILED, message = "No ig.ini found at or above $path; profile validation needs an IG.")
        val key = keyFor(igRoot, packages)
        val generation = igInfo.generation(igRoot)
        val entry: Entry
        synchronized(entries) {
            var current = entries[key]
            if (current == null || current.generation != generation) {
                if (current != null) log.info("IG definition changed for {}; rebuilding profile validator", igRoot)
                current = Entry(generation)
                entries[key] = current
            }
            entry = current
            if (entry.state == ProfileEngineState.NOT_STARTED) {
                entry.state = ProfileEngineState.WARMING
                executor.execute { build(key, entry) }
            }
        }
        val state = entry.state
        return ProfileValidatorLookup(state, entry.validator.takeIf { state == ProfileEngineState.READY }, entry.message)
    }

    fun keyFor(
        igRoot: Path,
        packages: List<String>,
    ) = Key(igRoot, packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted())

    /** The state of the engine for [key], for tests and diagnostics. */
    fun state(key: Key): ProfileEngineState = synchronized(entries) { entries[key]?.state ?: ProfileEngineState.NOT_STARTED }

    private fun build(
        key: Key,
        entry: Entry,
    ) {
        try {
            val packages = mergePackages(igInfo.dependsOn(key.igRoot), key.packages)
            log.info("Building profile validator for {} with packages {}", key.igRoot, packages)
            entry.validator = builder(ProfileEngineSpec(key.igRoot, packages))
            entry.state = ProfileEngineState.READY
        } catch (e: Throwable) {
            val reason = e.message?.substringBefore(" {")?.take(300) ?: e.javaClass.simpleName
            log.warn("Profile validator build for {} failed: {}", key.igRoot, reason)
            entry.message = "Profile validation unavailable: $reason"
            entry.state = ProfileEngineState.FAILED
        }
    }
}
