package org.opencds.cqf.cql.ls.server.validation.profile

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.opencds.cqf.cql.ls.server.validation.testcase.TestCaseValidator

/**
 * The resources a test case may reference (its own plus shared ones), keyed by every form a local
 * reference can take: `Type/id`, the entry fullUrl, and `urn:uuid:<id>`. Built from resources
 * [TestCaseValidator] has already parsed, so no file is read twice.
 */
class CaseResourceIndex private constructor(private val byKey: Map<String, JsonObject>) {
    val keys: Set<String> get() = byKey.keys

    /** The resource a reference points at; `_history` versions are ignored. */
    fun lookup(reference: String): JsonObject? = byKey[reference.substringBefore("/_history/")]

    /** [lookup] serialized for the core engine's parser. */
    fun lookupJson(reference: String): String? = lookup(reference)?.let { gson.toJson(it) }

    companion object {
        private val gson = Gson()

        val EMPTY = CaseResourceIndex(emptyMap())

        /** Case resources win over shared ones with the same key. */
        fun of(resources: List<TestCaseValidator.RawResource>): CaseResourceIndex {
            val map = mutableMapOf<String, JsonObject>()
            for (res in resources) {
                val keys =
                    listOfNotNull(
                        res.id?.let { "${res.resourceType}/$it" },
                        res.id?.let { "urn:uuid:$it" },
                        res.fullUrl,
                    )
                keys.forEach { map.putIfAbsent(it, res.jsonObject) }
            }
            return CaseResourceIndex(map)
        }
    }
}
