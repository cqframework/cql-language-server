package org.opencds.cqf.cql.ls.server.validation.spike

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.hl7.fhir.r5.context.IWorkerContext
import org.hl7.fhir.r5.elementmodel.Element
import org.hl7.fhir.r5.elementmodel.Manager
import org.hl7.fhir.r5.model.CanonicalResource
import org.hl7.fhir.r5.model.CanonicalType
import org.hl7.fhir.r5.utils.validation.IResourceValidator
import org.hl7.fhir.r5.utils.validation.IValidationPolicyAdvisor
import org.hl7.fhir.r5.utils.validation.IValidatorResourceFetcher
import org.hl7.fhir.r5.utils.validation.constants.ReferenceValidationPolicy
import org.hl7.fhir.validation.instance.InstanceValidator
import org.hl7.fhir.validation.instance.advisor.BasePolicyAdvisorForFullValidation
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidationIssue
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import kotlin.io.path.name
import kotlin.io.path.readText

// SPIKE ONLY: throwaway prototype code for evaluating the HL7 core validator. Not production code.

/** One prototype engine under evaluation. */
interface SpikeEngine {
    val name: String

    /** Validates one JSON file. [index] is the case's resources (case folder + shared) for the fetcher. */
    fun validateFile(
        file: Path,
        index: CaseIndex,
    ): List<ValidationIssue>
}

/** Index of the resources a case may reference: case folder, `shared/` and configured shared dirs. */
class CaseIndex private constructor(private val byKey: Map<String, String>) {
    val size: Int get() = byKey.values.toSet().size

    fun lookup(ref: String): String? {
        val unversioned = ref.substringBefore("/_history/")
        return byKey[unversioned]
    }

    companion object {
        private val gson = GsonBuilder().create()

        fun of(dirs: List<Path>): CaseIndex {
            val map = mutableMapOf<String, String>()
            for (dir in dirs) {
                if (!Files.isDirectory(dir)) continue
                Files.list(dir).use { s -> s.filter { it.name.endsWith(".json") }.toList() }.forEach { file ->
                    val parsed =
                        try {
                            JsonParser.parseString(file.readText())
                        } catch (e: Exception) {
                            return@forEach
                        }
                    if (!parsed.isJsonObject) return@forEach
                    val obj = parsed.asJsonObject
                    val type = obj.get("resourceType")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                    if (type == "Bundle") {
                        obj.getAsJsonArray("entry")?.forEach { e ->
                            val entry = e.asJsonObject
                            val res = entry.getAsJsonObject("resource") ?: return@forEach
                            add(map, res, entry.get("fullUrl")?.asString)
                        }
                    } else {
                        add(map, obj, null)
                    }
                }
            }
            return CaseIndex(map)
        }

        private fun add(
            map: MutableMap<String, String>,
            res: JsonObject,
            fullUrl: String?,
        ) {
            val json = gson.toJson(res)
            val type = res.get("resourceType").asString
            res.get("id")?.asString?.let { map["$type/$it"] = json }
            if (fullUrl != null) map[fullUrl] = json
        }
    }
}

fun isAbsoluteUrl(url: String?): Boolean =
    url != null && (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("urn:oid:"))

/**
 * Resolves references only from [index] (never the network). Modelled on StandAloneValidatorFetcher,
 * minus its single-directory read and its http fetches.
 */
class NoNetworkFetcher : IValidatorResourceFetcher {
    @Volatile var index: CaseIndex = CaseIndex.of(emptyList())
    val fetched = mutableListOf<String>()
    val refusedAbsolute = mutableListOf<String>()

    override fun fetch(
        validator: IResourceValidator?,
        appContext: Any?,
        url: String?,
    ): Element? {
        if (url == null) return null
        if (isAbsoluteUrl(url)) {
            refusedAbsolute.add(url)
            return null
        }
        val json = index.lookup(url) ?: return null
        val ctx: IWorkerContext = (validator as? InstanceValidator)?.context ?: return null
        fetched.add(url)
        return Manager.parseSingle(ctx, json.byteInputStream(), Manager.FhirFormat.JSON)
    }

    override fun resolveURL(
        validator: IResourceValidator?,
        appContext: Any?,
        path: String?,
        url: String?,
        rules: IWorkerContext.VersionResolutionRules?,
        type: String?,
        canonical: Boolean,
        targets: MutableList<CanonicalType>?,
    ): Boolean {
        if (url == null) return false
        // Absolute URLs (canonicals, system URIs) are not checked: we never go to the network.
        if (isAbsoluteUrl(url) || url.startsWith("urn:")) return true
        return index.lookup(url) != null
    }

    override fun fetchRaw(
        validator: IResourceValidator?,
        url: String?,
    ): ByteArray? = null

    override fun setLocale(locale: Locale?): IValidatorResourceFetcher = this

    override fun fetchCanonicalResource(
        validator: IResourceValidator?,
        appContext: Any?,
        url: String?,
    ): CanonicalResource? = null

    override fun fetchesCanonicalResource(
        validator: IResourceValidator?,
        url: String?,
    ): Boolean = false

    override fun fetchCanonicalResourceVersions(
        validator: IResourceValidator?,
        appContext: Any?,
        url: String?,
    ): MutableSet<IValidatorResourceFetcher.ResourceVersionInformation> = mutableSetOf()
}

/** Default IGNORE; relative + urn:uuid references get [localPolicy]; absolute URLs are ignored. */
class ReferenceCheckingPolicyAdvisor(var localPolicy: ReferenceValidationPolicy) :
    BasePolicyAdvisorForFullValidation(ReferenceValidationPolicy.IGNORE, emptySet()) {
    override fun policyForReference(
        validator: IResourceValidator?,
        appContext: Any?,
        path: String?,
        url: String?,
        destinationType: IValidationPolicyAdvisor.ReferenceDestinationType?,
    ): ReferenceValidationPolicy = if (url == null || isAbsoluteUrl(url)) ReferenceValidationPolicy.IGNORE else localPolicy

    override fun getReferencePolicy(): ReferenceValidationPolicy = localPolicy
}

/** Copies a real case folder into [dest] (read-only source). */
fun copyCase(
    src: Path,
    dest: Path,
): Path {
    Files.createDirectories(dest)
    Files.list(src).use { s -> s.filter { Files.isRegularFile(it) }.toList() }.forEach {
        Files.copy(it, dest.resolve(it.name))
    }
    return dest
}

/**
 * Builds a MADiE-style transaction bundle from a flat case folder: each entry gets a urn:uuid
 * fullUrl and every `Type/id` reference to a bundled resource is rewritten to that urn:uuid.
 */
fun buildTransactionBundle(caseDir: Path): String {
    val gson = GsonBuilder().setPrettyPrinting().create()
    val resources =
        Files.list(caseDir).use { s -> s.filter { it.name.endsWith(".json") }.sorted().toList() }
            .mapNotNull { runCatching { JsonParser.parseString(it.readText()).asJsonObject }.getOrNull() }
            .filter { it.has("resourceType") }
    val uuidFor = mutableMapOf<String, String>()
    for (r in resources) {
        val key = "${r.get("resourceType").asString}/${r.get("id").asString}"
        val id = r.get("id").asString
        uuidFor[key] = if (runCatching { UUID.fromString(id) }.isSuccess) id else UUID.randomUUID().toString()
    }

    fun rewrite(e: JsonElement) {
        if (e.isJsonObject) {
            val o = e.asJsonObject
            for ((k, v) in o.entrySet().toList()) {
                if (k == "reference" && v.isJsonPrimitive) {
                    uuidFor[v.asString]?.let { o.addProperty("reference", "urn:uuid:$it") }
                } else {
                    rewrite(v)
                }
            }
        } else if (e.isJsonArray) {
            e.asJsonArray.forEach { rewrite(it) }
        }
    }
    val entries = JsonArray()
    for (r in resources) {
        rewrite(r)
        val type = r.get("resourceType").asString
        val id = r.get("id").asString
        entries.add(
            JsonObject().apply {
                addProperty("fullUrl", "urn:uuid:${uuidFor["$type/$id"]}")
                add("resource", r)
                add(
                    "request",
                    JsonObject().apply {
                        addProperty("method", "PUT")
                        addProperty("url", "$type/$id")
                    },
                )
            },
        )
    }
    val bundle =
        JsonObject().apply {
            addProperty("resourceType", "Bundle")
            addProperty("id", UUID.randomUUID().toString())
            addProperty("type", "transaction")
            add("entry", entries)
        }
    return gson.toJson(bundle)
}

/** 1-based line of the first line containing [needle], or null. */
fun lineOf(
    text: String,
    needle: String,
): Int? = text.lines().indexOfFirst { it.contains(needle) }.takeIf { it >= 0 }?.plus(1)
