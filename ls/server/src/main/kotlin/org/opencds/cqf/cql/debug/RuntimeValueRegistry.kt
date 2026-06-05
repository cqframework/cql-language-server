package org.opencds.cqf.cql.debug

import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.engine.execution.State
import org.slf4j.LoggerFactory
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap

enum class RuntimeValueCategory {
    PARAMETER,
    CONTEXT_RESOURCE,
    DEFINE,
    STACK_VARIABLE,
}

class RuntimeValue(
    val name: String,
    val value: Any?,
    val category: RuntimeValueCategory,
    val libraryName: String? = null,
    val type: String? = null,
)

/**
 * Central registry for all debug-time variable values.
 *
 * Lifecycle:
 * ┌──────────────────┬────────────┬────────────────────────────────┐
 * │     Category     │   Bucket   │           Cleared by           │
 * ├──────────────────┼────────────┼────────────────────────────────┤
 * │ PARAMETER        │ persistent │ reset() only                   │
 * │ CONTEXT_RESOURCE │ persistent │ reset() only                   │
 * │ DEFINE           │ persistent │ reset() only                   │
 * │ STACK_VARIABLE   │ transient  │ clearStackVariables() at pause │
 * └──────────────────┴────────────┴────────────────────────────────┘
 *
 * Known limitation: when the same short name exists in multiple libraries (e.g. "Numerator"),
 * the first match by category priority is returned. Library-qualified disambiguation via DAP
 * frameId is deferred.
 */
class RuntimeValueRegistry {
    private val persistent = ConcurrentHashMap<String, RuntimeValue>()
    private val transient = ConcurrentHashMap<String, RuntimeValue>()

    /** Secondary index: short name → set of composite keys, for O(1) [find] lookups. */
    private val nameIndex = ConcurrentHashMap<String, MutableSet<String>>()

    private var parametersLoaded = false

    companion object {
        private val log = LoggerFactory.getLogger(RuntimeValueRegistry::class.java)
    }

    private fun key(
        category: RuntimeValueCategory,
        libraryName: String?,
        name: String,
    ): String = "${category.name}|${libraryName ?: ""}|$name"

    private fun put(
        category: RuntimeValueCategory,
        libraryName: String?,
        name: String,
        value: Any?,
        type: String?,
    ) {
        val k = key(category, libraryName, name)
        val rv = RuntimeValue(name, value, category, libraryName, type)
        when (category) {
            RuntimeValueCategory.STACK_VARIABLE -> {
                transient[k] = rv
                nameIndex.getOrPut(name) { LinkedHashSet() }.add(k)
            }
            else -> {
                persistent[k] = rv
                nameIndex.getOrPut(name) { LinkedHashSet() }.add(k)
            }
        }
    }

    /**
     * Loads parameters from the engine state.
     * Idempotent — only loads once per session.
     *
     * @param paramTypes library name → (parameter name → type string), e.g. from [CqlDebugServer.extractParameterMetadata]
     */
    fun loadParameters(
        state: State,
        paramTypes: Map<String, Map<String, String>> = emptyMap(),
    ) {
        if (parametersLoaded) {
            log.debug("loadParameters: already loaded, skipping")
            return
        }
        for ((fullName, value) in state.parameters) {
            val dotIndex = fullName.indexOf('.')
            val libraryName: String?
            val paramName: String
            if (dotIndex > 0) {
                libraryName = fullName.substring(0, dotIndex)
                paramName = fullName.substring(dotIndex + 1)
            } else {
                libraryName = "(Global)"
                paramName = fullName
            }
            val type = paramTypes[libraryName]?.get(paramName)
            val displayValue = value
            put(RuntimeValueCategory.PARAMETER, libraryName, paramName, displayValue, type)
        }
        parametersLoaded = true
        log.debug("loadParameters: loaded {} parameters", state.parameters.size)
    }

    /**
     * Loads or overwrites a context resource.
     *
     * Called at every pause, not just the first — in population-level runs the
     * patient changes per context, so overwriting is correct behavior.
     */
    fun loadContextResource(
        name: String,
        value: Any?,
        type: String?,
    ) {
        put(RuntimeValueCategory.CONTEXT_RESOURCE, null, name, value, type)
    }

    fun putStackVariable(
        name: String,
        value: Any?,
        type: String?,
    ) {
        put(RuntimeValueCategory.STACK_VARIABLE, null, name, value, type)
    }

    fun putDefine(
        name: String,
        value: Any?,
        type: String?,
        libraryId: VersionedIdentifier?,
    ) {
        put(RuntimeValueCategory.DEFINE, libraryId?.id, name, value, type)
    }

    /** Clears transient values (STACK_VARIABLE) and prunes stale entries from the name index. Called at each pause before re-populating. */
    fun clearStackVariables() {
        val prefix = "${RuntimeValueCategory.STACK_VARIABLE.name}|"
        for (k in transient.keys) {
            val name = k.substringAfterLast("|")
            nameIndex[name]?.remove(k)
            if (nameIndex[name]?.isEmpty() == true) nameIndex.remove(name)
        }
        transient.clear()
    }

    /** Clears all values, including persistent. Called on session restart. */
    fun reset() {
        persistent.clear()
        transient.clear()
        nameIndex.clear()
        parametersLoaded = false
    }

    /**
     * Finds a value by display name searching categories in CQL scoping priority:
     * STACK_VARIABLE > DEFINE > CONTEXT_RESOURCE > PARAMETER.
     *
     * Uses a secondary name index for O(1) name lookups with exact key matching,
     * eliminating false-positives from substring suffix matches.
     */
    fun find(name: String): RuntimeValue? {
        val keys = nameIndex[name] ?: return null

        // Check transient (STACK_VARIABLE) first by iterating index keys
        val transientPrefix = "${RuntimeValueCategory.STACK_VARIABLE.name}|"
        for (k in keys) {
            if (k.startsWith(transientPrefix)) {
                transient[k]?.let { return it }
            }
        }

        // Check persistent categories in priority order
        for (cat in listOf(
            RuntimeValueCategory.DEFINE,
            RuntimeValueCategory.CONTEXT_RESOURCE,
            RuntimeValueCategory.PARAMETER,
        )) {
            val prefix = "${cat.name}|"
            for (k in keys) {
                if (k.startsWith(prefix)) {
                    persistent[k]?.let { return it }
                }
            }
        }
        return null
    }

    /**
     * Finds a value by display name scoped to a specific library.
     * Only searches persistent categories (DEFINE, CONTEXT_RESOURCE, PARAMETER).
     * Returns null when [libraryName] is non-null and no matching entry is found
     * (does not fall back to unscoped search).
     */
    fun find(
        name: String,
        libraryName: String?,
    ): RuntimeValue? {
        val keys = nameIndex[name] ?: return null

        val expectedPrefix = if (libraryName != null) "${RuntimeValueCategory.DEFINE.name}|$libraryName|$name" else null
        if (expectedPrefix != null) {
            val rv = persistent[expectedPrefix]
            if (rv != null) return rv
        }

        for (cat in listOf(
            RuntimeValueCategory.DEFINE,
            RuntimeValueCategory.CONTEXT_RESOURCE,
            RuntimeValueCategory.PARAMETER,
        )) {
            val prefix = "${cat.name}|${libraryName ?: ""}|"
            for (k in keys) {
                if (k.startsWith(prefix)) {
                    persistent[k]?.let { return it }
                }
            }
        }
        return null
    }

    fun getStackVariables(): List<RuntimeValue> = transient.values.toList()

    fun getContextResources(): List<RuntimeValue> =
        persistent.values.filter { it.category == RuntimeValueCategory.CONTEXT_RESOURCE }

    fun getDefines(): List<RuntimeValue> =
        persistent.values.filter { it.category == RuntimeValueCategory.DEFINE }.sortedBy { it.name }

    fun getParametersByLibrary(): Map<String, List<RuntimeValue>> =
        persistent.values
            .filter { it.category == RuntimeValueCategory.PARAMETER }
            .groupBy { it.libraryName ?: "(Global)" }
}
