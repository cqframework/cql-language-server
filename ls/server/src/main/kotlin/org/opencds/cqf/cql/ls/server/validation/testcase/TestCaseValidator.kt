package org.opencds.cqf.cql.ls.server.validation.testcase

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import org.opencds.cqf.cql.ls.server.validation.profile.CaseResourceIndex
import org.opencds.cqf.cql.ls.server.validation.profile.CoreEngineProfileValidator
import org.opencds.cqf.cql.ls.server.validation.profile.EngineMode
import org.opencds.cqf.cql.ls.server.validation.profile.ProfileEngineState
import org.opencds.cqf.cql.ls.server.validation.profile.ProfileIssuePolicy
import org.opencds.cqf.cql.ls.server.validation.profile.ProfileValidatorLookup
import org.opencds.cqf.cql.ls.server.validation.profile.ProfileValidatorProvider
import org.opencds.cqf.cql.ls.server.validation.profile.ResourceProfileValidator
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Validates test cases with custom case-level checks and, when [profileValidators] is set and a
 * request asks for it, FHIR profile validation by the core engine.
 */
class TestCaseValidator(private val profileValidators: ProfileValidatorProvider? = null) {
    companion object {
        private val log = LoggerFactory.getLogger(TestCaseValidator::class.java)

        /** Custom checks the core engine covers; dropped for files the engine validated. */
        val COVERED_BY_CORE = setOf("ERR_DANGLING_REFERENCE", "ERR_MISSING_REQUIRED_FIELD", "WARN_MISSING_REQUIRED_FIELD", "WARN_FULLURL_MISMATCH")

        const val WARMING_MESSAGE = "Profile validation is starting; results include custom checks only. Run again shortly."

        private val RESERVED_DIR_NAMES = setOf("results", "__MACOSX", ".git", ".cql-debug", "shared")
        private val SLASH_DRIVE_LETTER = Regex("^/[A-Za-z]:")
        private val GSON_LINE = Regex("line (\\d+)")

        fun normalizePath(pathStr: String): Path {
            return if (pathStr.startsWith("file:")) {
                try {
                    Paths.get(URI.create(pathStr.replace(" ", "%20")))
                } catch (e: Exception) {
                    val stripped = pathStr.removePrefix("file://").removePrefix("file:")
                    // `/C:/...` is a Windows drive path carried over from the URI form
                    Paths.get(if (SLASH_DRIVE_LETTER.containsMatchIn(stripped)) stripped.substring(1) else stripped)
                }
            } else {
                Paths.get(pathStr)
            }
        }
    }

    data class RawResource(
        val resourceType: String,
        val id: String?,
        val fullUrl: String?,
        val filePath: Path,
        val jsonObject: JsonObject,
        val lines: List<String>,
        val jsonPathPrefix: String = "",
        // 1-based line where this resource starts in its file, used to anchor line lookups
        val anchorLine: Int = 1,
    )

    data class ParsedFile(
        val resources: List<RawResource>,
        val issue: ValidationIssue? = null,
    )

    /** A request's profile validation: the engine mode and what the provider handed out. */
    private data class ProfileRun(val mode: EngineMode, val lookup: ProfileValidatorLookup) {
        val status: ProfileStatus
            get() =
                when (lookup.state) {
                    ProfileEngineState.READY -> ProfileStatus.RAN
                    ProfileEngineState.FAILED -> ProfileStatus.FAILED
                    else -> ProfileStatus.WARMING
                }
    }

    data class DiscoveredCase(
        val name: String,
        val casePath: Path,
        val resources: List<RawResource>,
        val sharedResources: List<RawResource>,
        val madieManifestPath: Path? = null,
        val parseIssues: List<ValidationIssue> = emptyList(),
    )

    fun validate(request: ValidateTestCaseRequest): ValidateTestCaseResponse {
        val options = request.options ?: ValidateTestCaseOptions()
        val targetPath = normalizePath(request.path)

        if (!targetPath.exists()) {
            return ValidateTestCaseResponse(
                targetPath = targetPath.toString(),
                testCaseCount = 0,
                resourceCount = 0,
                errorCount = 1,
                warningCount = 0,
                testCases =
                    listOf(
                        TestCaseValidationResult(
                            testCasePath = targetPath.toString(),
                            testCaseName = targetPath.name,
                            patientId = null,
                            resourceCount = 0,
                            errorCount = 1,
                            warningCount = 0,
                            issues =
                                listOf(
                                    ValidationIssue(
                                        severity = ValidationSeverity.ERROR,
                                        code = "ERR_PATH_NOT_FOUND",
                                        message = "Target path does not exist: $targetPath",
                                        filePath = targetPath.toString(),
                                        line = 1,
                                    ),
                                ),
                        ),
                    ),
            )
        }

        val discoveredCases = discoverTestCases(targetPath, options)
        val profileRun = startProfileRun(targetPath, options)
        val caseResults = discoveredCases.map { validateCase(it, options, profileRun) }

        val totalErrors = caseResults.sumOf { it.errorCount }
        val totalWarnings = caseResults.sumOf { it.warningCount }
        val totalResources = caseResults.sumOf { it.resourceCount }

        return ValidateTestCaseResponse(
            targetPath = targetPath.toString(),
            testCaseCount = caseResults.size,
            resourceCount = totalResources,
            errorCount = totalErrors,
            warningCount = totalWarnings,
            testCases = caseResults,
            profileStatus = profileRun?.status ?: ProfileStatus.OFF,
            profileMessage = profileRun?.let { run -> responseProfileMessage(run, caseResults) },
        )
    }

    private fun startProfileRun(
        targetPath: Path,
        options: ValidateTestCaseOptions,
    ): ProfileRun? {
        val mode =
            when (options.profile) {
                ProfileMode.QUICK -> EngineMode.QUICK
                ProfileMode.FULL -> EngineMode.FULL
                ProfileMode.OFF, null -> return null
            }
        val lookup =
            profileValidators?.acquire(targetPath, options.packages.orEmpty(), mode)
                ?: ProfileValidatorLookup(ProfileEngineState.FAILED, message = "Profile validation is not available in this language server.")
        return ProfileRun(mode, lookup)
    }

    private fun responseProfileMessage(
        run: ProfileRun,
        caseResults: List<TestCaseValidationResult>,
    ): String? =
        when (run.status) {
            ProfileStatus.WARMING -> run.lookup.message ?: WARMING_MESSAGE
            ProfileStatus.RAN -> caseResults.firstNotNullOfOrNull { it.profileMessage }
            else -> run.lookup.message
        }

    private fun discoverTestCases(
        targetPath: Path,
        options: ValidateTestCaseOptions,
    ): List<DiscoveredCase> {
        val cases = mutableListOf<DiscoveredCase>()

        if (targetPath.isRegularFile() && targetPath.name.endsWith(".json", ignoreCase = true)) {
            val parsed = parseJsonFile(targetPath)
            if (parsed.resources.isNotEmpty() || parsed.issue != null) {
                val shared = loadSharedResources(targetPath.parent, options)
                cases.add(
                    DiscoveredCase(
                        name = targetPath.name.removeSuffix(".json"),
                        casePath = targetPath,
                        resources = parsed.resources,
                        sharedResources = shared,
                        parseIssues = listOfNotNull(parsed.issue),
                    ),
                )
            }
            return cases
        }

        if (targetPath.isDirectory()) {
            // Check if this directory directly contains resource JSON files (or JSON files that failed to parse)
            val directParsed = listJsonFiles(targetPath).map { parseJsonFile(it) }

            if (directParsed.any { it.resources.isNotEmpty() || it.issue != null }) {
                // Single test case directory
                val shared = loadSharedResources(targetPath.parent, options)
                val madieFile = findMadieManifest(targetPath.parent)
                cases.add(
                    DiscoveredCase(
                        name = targetPath.name,
                        casePath = targetPath,
                        resources = directParsed.flatMap { it.resources },
                        sharedResources = shared,
                        madieManifestPath = madieFile,
                        parseIssues = directParsed.mapNotNull { it.issue },
                    ),
                )
                return cases
            }

            // Suite or container directory: inspect subdirectories
            val shared = loadSharedResources(targetPath, options)
            val madieFile = findMadieManifest(targetPath)

            try {
                Files.list(targetPath).use { stream ->
                    stream.filter { Files.isDirectory(it) && !RESERVED_DIR_NAMES.contains(it.name) && !it.name.startsWith(".") }
                        .sorted()
                        .forEach { subDir ->
                            val subParsed = listJsonFiles(subDir).map { parseJsonFile(it) }
                            if (subParsed.any { it.resources.isNotEmpty() || it.issue != null }) {
                                cases.add(
                                    DiscoveredCase(
                                        name = subDir.name,
                                        casePath = subDir,
                                        resources = subParsed.flatMap { it.resources },
                                        sharedResources = shared,
                                        madieManifestPath = madieFile,
                                        parseIssues = subParsed.mapNotNull { it.issue },
                                    ),
                                )
                            }
                        }
                }
            } catch (e: Exception) {
                log.warn("Error scanning test cases in {}: {}", targetPath, e.message)
            }
        }

        return cases
    }

    private fun listJsonFiles(dir: Path): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return try {
            Files.list(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.name.endsWith(".json", ignoreCase = true) }
                    .toList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun loadSharedResources(
        dir: Path?,
        options: ValidateTestCaseOptions,
    ): List<RawResource> {
        val sharedList = mutableListOf<RawResource>()
        if (dir != null) {
            val sharedDir = dir.resolve("shared")
            if (Files.isDirectory(sharedDir)) {
                listJsonFiles(sharedDir).forEach { sharedList.addAll(parseJsonFile(it).resources) }
            }
        }
        options.sharedDirs?.forEach { sharedPathStr ->
            val p = normalizePath(sharedPathStr)
            if (Files.isDirectory(p)) {
                listJsonFiles(p).forEach { sharedList.addAll(parseJsonFile(it).resources) }
            }
        }
        return sharedList
    }

    private fun findMadieManifest(dir: Path?): Path? {
        if (dir == null) return null
        val direct = dir.resolve(".madie")
        if (Files.isRegularFile(direct)) return direct
        val parent = dir.parent?.resolve(".madie")
        if (parent != null && Files.isRegularFile(parent)) return parent
        return null
    }

    private fun parseJsonFile(filePath: Path): ParsedFile {
        val text =
            try {
                filePath.readText()
            } catch (e: Exception) {
                log.warn("Failed to read JSON file {}: {}", filePath, e.message)
                return ParsedFile(emptyList())
            }
        val lines = text.lines()

        fun invalidJson(
            message: String,
            line: Int,
        ) = ValidationIssue(
            severity = ValidationSeverity.ERROR,
            code = "ERR_INVALID_JSON",
            message = message,
            filePath = filePath.toString(),
            line = line,
        )

        return try {
            val parsed = JsonParser.parseString(text)
            if (!parsed.isJsonObject) return ParsedFile(emptyList())

            val obj = parsed.asJsonObject
            // A JSON object without resourceType (e.g. config.json) is not a FHIR resource; ignore it
            val typeElement = obj.get("resourceType") ?: return ParsedFile(emptyList())
            val type =
                typeElement.stringOrNull()
                    ?: return ParsedFile(
                        emptyList(),
                        invalidJson(
                            "Invalid FHIR JSON in ${filePath.name}: 'resourceType' must be a string.",
                            findLine(lines, Regex("\"resourceType\"\\s*:")) ?: 1,
                        ),
                    )

            if (type == "Bundle") {
                val entries = obj.getAsJsonArray("entry") ?: return ParsedFile(emptyList())
                val list = mutableListOf<RawResource>()
                var issue: ValidationIssue? = null
                var previousAnchor = 1
                for (i in 0 until entries.size()) {
                    val entry = entries.get(i)
                    if (entry.isJsonObject) {
                        val entryObj = entry.asJsonObject
                        val res = entryObj.get("resource")?.takeIf { it.isJsonObject }?.asJsonObject
                        val fullUrl = entryObj.get("fullUrl")?.stringOrNull()
                        val resTypeElement = res?.get("resourceType") ?: continue
                        val resType = resTypeElement.stringOrNull()
                        if (resType == null) {
                            issue =
                                issue ?: invalidJson(
                                    "Invalid FHIR JSON in ${filePath.name}: entry[$i].resource.resourceType must be a string.",
                                    findLine(lines, Regex("\"resourceType\"\\s*:\\s*[^\"\\s]"), previousAnchor - 1) ?: 1,
                                )
                            continue
                        }
                        val id = res.get("id")?.stringOrNull()
                        val anchor =
                            id?.let { findLineFrom(lines, Regex("\"id\"\\s*:\\s*\"${Regex.escape(it)}\""), previousAnchor - 1) }
                                ?: findLineFrom(lines, Regex("\"resourceType\"\\s*:\\s*\"${Regex.escape(resType)}\""), previousAnchor - 1)
                                ?: 1
                        previousAnchor = maxOf(previousAnchor, anchor)
                        list.add(
                            RawResource(
                                resourceType = resType,
                                id = id,
                                fullUrl = fullUrl,
                                filePath = filePath,
                                jsonObject = res,
                                lines = lines,
                                jsonPathPrefix = "entry[$i].resource",
                                anchorLine = anchor,
                            ),
                        )
                    }
                }
                ParsedFile(list, issue)
            } else {
                ParsedFile(
                    listOf(
                        RawResource(
                            resourceType = type,
                            id = obj.get("id")?.stringOrNull(),
                            fullUrl = null,
                            filePath = filePath,
                            jsonObject = obj,
                            lines = lines,
                            anchorLine = findLine(lines, Regex("\"resourceType\"\\s*:\\s*\"${Regex.escape(type)}\"")) ?: 1,
                        ),
                    ),
                )
            }
        } catch (e: JsonParseException) {
            log.warn("Failed to parse JSON file {}: {}", filePath, e.message)
            val line = e.message?.let { GSON_LINE.find(it) }?.groupValues?.get(1)?.toIntOrNull() ?: 1
            ParsedFile(emptyList(), invalidJson("Invalid JSON in ${filePath.name}: ${e.cause?.message ?: e.message}", line))
        } catch (e: Exception) {
            log.warn("Failed to parse JSON file {}: {}", filePath, e.message)
            ParsedFile(emptyList())
        }
    }

    private fun JsonElement.stringOrNull(): String? = if (isJsonPrimitive && asJsonPrimitive.isString) asString else null

    private fun validateCase(
        case: DiscoveredCase,
        options: ValidateTestCaseOptions,
        profileRun: ProfileRun? = null,
    ): TestCaseValidationResult {
        val issues = case.parseIssues.toMutableList()

        // 1. Identify patients
        val patients = case.resources.filter { it.resourceType == "Patient" }
        val patientId =
            when {
                patients.isEmpty() -> {
                    issues.add(
                        ValidationIssue(
                            severity = ValidationSeverity.ERROR,
                            code = "ERR_NO_PATIENT",
                            message = "Test case '${case.name}' contains no Patient resource.",
                            filePath = case.casePath.toString(),
                            line = 1,
                        ),
                    )
                    null
                }
                patients.size > 1 -> {
                    val ids = patients.mapNotNull { it.id }.joinToString(", ")
                    issues.add(
                        ValidationIssue(
                            severity = ValidationSeverity.ERROR,
                            code = "ERR_MULTIPLE_PATIENTS",
                            message = "Test case '${case.name}' contains multiple Patient resources ($ids). Expected exactly one Patient.",
                            filePath = patients.first().filePath.toString(),
                            line = patients.first().anchorLine,
                        ),
                    )
                    patients.first().let { it.id ?: it.fullUrlUuid() }
                }
                else -> patients.first().let { it.id ?: it.fullUrlUuid() }
            }
        // Every form a reference to the test case patient may take: id, fullUrl, and the fullUrl's urn:uuid
        val patientIdentities =
            patients.firstOrNull()?.let { patient ->
                setOfNotNull(patient.id, patient.fullUrl, patient.fullUrl?.let { normalizeReferenceId(it) })
            } ?: emptySet()

        // 2. Duplicate Resource IDs check
        if (options.checkDuplicateIds) {
            val byTypeAndId = case.resources.filter { it.id != null }.groupBy { Pair(it.resourceType, it.id!!) }
            for ((key, duplicates) in byTypeAndId) {
                if (duplicates.size > 1) {
                    val (type, id) = key
                    val files = duplicates.map { it.filePath.name }.distinct().joinToString(", ")
                    for (dup in duplicates) {
                        issues.add(
                            ValidationIssue(
                                severity = ValidationSeverity.ERROR,
                                code = "ERR_DUPLICATE_ID",
                                message = "Duplicate resource identifier: $type/$id found across files: $files",
                                resourceType = type,
                                resourceId = id,
                                filePath = dup.filePath.toString(),
                                jsonPath = if (dup.jsonPathPrefix.isNotEmpty()) "${dup.jsonPathPrefix}.id" else "id",
                                line = findLine(dup.lines, Regex("\"id\"\\s*:\\s*\"${Regex.escape(id)}\""), dup.anchorLine - 1),
                            ),
                        )
                    }
                }
            }
        }

        // Build target index for reference resolution
        val localResourceMap = mutableSetOf<Pair<String, String>>()
        val allUuids = mutableSetOf<String>()
        val fullUrls = mutableSetOf<String>()

        fun indexResources(resources: List<RawResource>) {
            for (res in resources) {
                if (res.id != null) {
                    localResourceMap.add(Pair(res.resourceType, res.id))
                    allUuids.add(res.id)
                }
                if (res.fullUrl != null) {
                    fullUrls.add(res.fullUrl)
                    if (res.fullUrl.startsWith("urn:uuid:")) {
                        allUuids.add(res.fullUrl.removePrefix("urn:uuid:"))
                    }
                }
            }
        }

        indexResources(case.resources)
        indexResources(case.sharedResources)

        // 3. Single-Patient Linkage check
        if (options.checkPatientLinkage && patientId != null) {
            for (res in case.resources) {
                if (res.resourceType == "Patient") continue
                // MeasureReport subjects are checked (once) by validateMeasureReport
                if (res.resourceType == "MeasureReport" && options.checkMadieStructure) continue

                // Check subject, patient, beneficiary
                val subjectField = listOf("subject", "patient", "beneficiary").firstOrNull { extractReferenceTarget(res.jsonObject, it) != null }
                val subjectRef = subjectField?.let { extractReferenceTarget(res.jsonObject, it) }

                if (subjectRef != null) {
                    val normalizedTargetId = normalizeReferenceId(subjectRef)
                    if (isPatientReference(subjectRef) && !isTestCasePatient(subjectRef, patientIdentities)) {
                        issues.add(
                            ValidationIssue(
                                severity = ValidationSeverity.ERROR,
                                code = "ERR_PATIENT_MISMATCH",
                                message = "${res.resourceType}/${res.id ?: "unknown"} references Patient '$normalizedTargetId', but test case patient is '$patientId'.",
                                resourceType = res.resourceType,
                                resourceId = res.id,
                                filePath = res.filePath.toString(),
                                jsonPath = if (res.jsonPathPrefix.isNotEmpty()) "${res.jsonPathPrefix}.$subjectField.reference" else "$subjectField.reference",
                                line =
                                    findLine(
                                        res.lines,
                                        Regex("\"reference\"\\s*:\\s*\"${Regex.escape(subjectRef)}\""),
                                        res.anchorLine - 1,
                                    ),
                            ),
                        )
                    }
                }
            }
        }

        // 4. Referential Integrity (Dangling references)
        if (options.checkReferences) {
            for (res in case.resources) {
                val refs = mutableListOf<Pair<String, String>>() // (jsonPath, refValue)
                collectReferences(res.jsonObject, "", refs)
                val containedIds by lazy {
                    res.jsonObject.get("contained")?.takeIf { it.isJsonArray }?.asJsonArray
                        ?.mapNotNull { it.takeIf { c -> c.isJsonObject }?.asJsonObject?.get("id")?.stringOrNull() }
                        ?.toSet() ?: emptySet()
                }

                for ((jsonPath, refValue) in refs) {
                    if (isIgnoredReference(refValue)) continue

                    val resolved =
                        if (refValue.startsWith("#")) {
                            // Local reference to a contained resource ("#" alone is the container itself)
                            refValue == "#" || containedIds.contains(refValue.substring(1))
                        } else if (refValue.startsWith("urn:uuid:")) {
                            val uuid = refValue.removePrefix("urn:uuid:")
                            allUuids.contains(uuid) || fullUrls.contains(refValue)
                        } else if (refValue.contains("/")) {
                            val parts = refValue.split("/", limit = 2)
                            val refType = parts[0]
                            val refId = parts[1].substringBefore("/_history/")
                            if (refType in setOf("Measure", "Library")) {
                                true
                            } else {
                                localResourceMap.contains(Pair(refType, refId))
                            }
                        } else {
                            // Bare ID
                            allUuids.contains(refValue)
                        }

                    if (!resolved) {
                        val fullJsonPath = if (res.jsonPathPrefix.isNotEmpty()) "${res.jsonPathPrefix}.$jsonPath" else jsonPath
                        issues.add(
                            ValidationIssue(
                                severity = ValidationSeverity.ERROR,
                                code = "ERR_DANGLING_REFERENCE",
                                message = "Dangling reference: '$refValue' in ${res.resourceType}/${res.id ?: "unknown"} cannot be resolved.",
                                resourceType = res.resourceType,
                                resourceId = res.id,
                                filePath = res.filePath.toString(),
                                jsonPath = fullJsonPath,
                                line = findLine(res.lines, Regex("\"reference\"\\s*:\\s*\"${Regex.escape(refValue)}\""), res.anchorLine - 1),
                            ),
                        )
                    }
                }
            }
        }

        // 5. MADiE Structure & MeasureReport Checks
        if (options.checkMadieStructure) {
            for (res in case.resources) {
                if (res.resourceType == "MeasureReport") {
                    validateMeasureReport(res, patientId, patientIdentities, issues)
                }

                // Check bundle fullUrl vs resource id
                if (res.fullUrl != null && res.id != null) {
                    val expectedEnding = "${res.resourceType}/${res.id}"
                    val expectedUrn = "urn:uuid:${res.id}"
                    if (!res.fullUrl.endsWith(expectedEnding) && res.fullUrl != expectedUrn && !res.fullUrl.endsWith("/${res.id}")) {
                        issues.add(
                            ValidationIssue(
                                severity = ValidationSeverity.WARNING,
                                code = "WARN_FULLURL_MISMATCH",
                                message = "Bundle entry fullUrl '${res.fullUrl}' does not match resource ${res.resourceType}/${res.id}.",
                                resourceType = res.resourceType,
                                resourceId = res.id,
                                filePath = res.filePath.toString(),
                                jsonPath = if (res.jsonPathPrefix.isNotEmpty()) "${res.jsonPathPrefix}.fullUrl" else "fullUrl",
                                line = findLine(res.lines, Regex("\"fullUrl\"\\s*:\\s*\"${Regex.escape(res.fullUrl)}\"")),
                            ),
                        )
                    }
                }
            }

            // Check .madie manifest sync if present
            if (case.madieManifestPath != null && Files.isRegularFile(case.madieManifestPath)) {
                validateMadieManifest(case, issues)
            }
        }

        // 6. Required Fields & Common Cardinality Traps
        if (options.checkRequiredFields) {
            for (res in case.resources) {
                checkRequiredFieldsForResource(res, issues)
            }
        }

        // 7. Profile validation by the core engine, when it is ready
        var suppressedCount = 0
        var profileMessage = profileRun?.lookup?.message ?: if (profileRun?.status == ProfileStatus.WARMING) WARMING_MESSAGE else null
        val profileValidator = profileRun?.lookup?.validator
        if (profileRun != null && profileValidator != null) {
            val result = runProfileValidation(case, options, profileRun.mode, profileValidator, issues)
            suppressedCount = result.suppressedCount
            profileMessage = result.hint
        }

        val errors = issues.count { it.severity == ValidationSeverity.ERROR }
        val warnings = issues.count { it.severity == ValidationSeverity.WARNING }

        return TestCaseValidationResult(
            testCasePath = case.casePath.toString(),
            testCaseName = case.name,
            patientId = patientId,
            resourceCount = case.resources.size,
            errorCount = errors,
            warningCount = warnings,
            issues = issues,
            profileStatus = profileRun?.status ?: ProfileStatus.OFF,
            profileMessage = profileMessage,
            suppressedCount = suppressedCount,
        )
    }

    private data class ProfileResult(val suppressedCount: Int, val hint: String?)

    /**
     * Runs the core engine on the case's files and merges its issues into [issues], dropping the
     * custom checks it covers for each file it validated. Files that failed to parse keep only the
     * custom ERR_INVALID_JSON (its line number is reliable) and are not sent to the engine.
     */
    private fun runProfileValidation(
        case: DiscoveredCase,
        options: ValidateTestCaseOptions,
        mode: EngineMode,
        validator: ResourceProfileValidator,
        issues: MutableList<ValidationIssue>,
    ): ProfileResult {
        val unparsable = case.parseIssues.mapNotNull { it.filePath }.toSet()
        val files = case.resources.map { it.filePath }.distinct().filter { it.toString() !in unparsable }
        if (files.isEmpty()) return ProfileResult(0, null)

        val coreIssues = validator.validate(files, CaseResourceIndex.of(case.resources + case.sharedResources), mode, options.checkReferences)
        val hint = ProfileIssuePolicy.unknownProfileHint(coreIssues)
        val filtered = ProfileIssuePolicy(mode, options.suppressMessageIds, options.downgradeMessageIds).apply(coreIssues)

        val engineFailed = coreIssues.filter { it.code == CoreEngineProfileValidator.VALIDATION_FAILED_CODE }.mapNotNull { it.filePath }.toSet()
        val validated = files.map { it.toString() }.toSet() - engineFailed
        issues.removeAll { it.code in COVERED_BY_CORE && it.filePath in validated }
        issues.addAll(filtered.issues)
        return ProfileResult(filtered.suppressedCount, hint)
    }

    private fun validateMeasureReport(
        res: RawResource,
        patientId: String?,
        patientIdentities: Set<String>,
        issues: MutableList<ValidationIssue>,
    ) {
        val obj = res.jsonObject

        // Check contained Parameters subject
        if (obj.has("contained") && obj.get("contained").isJsonArray) {
            val containedArray = obj.getAsJsonArray("contained")
            for (elem in containedArray) {
                if (elem.isJsonObject && elem.asJsonObject.get("resourceType")?.asString == "Parameters") {
                    val params = elem.asJsonObject.getAsJsonArray("parameter")
                    if (params != null) {
                        for (p in params) {
                            if (p.isJsonObject && p.asJsonObject.get("name")?.asString == "subject") {
                                val subjectVal = p.asJsonObject.get("valueString")?.asString
                                if (subjectVal != null && patientId != null) {
                                    if (!isTestCasePatient(subjectVal, patientIdentities)) {
                                        issues.add(
                                            ValidationIssue(
                                                severity = ValidationSeverity.ERROR,
                                                code = "ERR_MEASURE_REPORT_SUBJECT",
                                                message = "MeasureReport contained subject parameter '$subjectVal' does not match test case patient ID '$patientId'.",
                                                resourceType = "MeasureReport",
                                                resourceId = res.id,
                                                filePath = res.filePath.toString(),
                                                jsonPath = "contained[Parameters].parameter[subject].valueString",
                                                line = findLine(res.lines, Regex("\"valueString\"\\s*:\\s*\"${Regex.escape(subjectVal)}\""), res.anchorLine - 1),
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Check top-level subject
        val topSubject = extractReferenceTarget(obj, "subject")
        if (topSubject != null && patientId != null) {
            if (!isTestCasePatient(topSubject, patientIdentities)) {
                issues.add(
                    ValidationIssue(
                        severity = ValidationSeverity.ERROR,
                        code = "ERR_MEASURE_REPORT_SUBJECT",
                        message = "MeasureReport.subject '$topSubject' does not match test case patient ID '$patientId'.",
                        resourceType = "MeasureReport",
                        resourceId = res.id,
                        filePath = res.filePath.toString(),
                        jsonPath = "subject.reference",
                        line = findLine(res.lines, Regex("\"reference\"\\s*:\\s*\"${Regex.escape(topSubject)}\""), res.anchorLine - 1),
                    ),
                )
            }
        }
    }

    private fun validateMadieManifest(
        case: DiscoveredCase,
        issues: MutableList<ValidationIssue>,
    ) {
        try {
            val content = case.madieManifestPath!!.readText()
            val parsed = JsonParser.parseString(content)
            if (parsed.isJsonArray) {
                val array = parsed.asJsonArray
                val registeredIds = mutableSetOf<String>()
                for (item in array) {
                    if (item.isJsonObject) {
                        val itemObj = item.asJsonObject
                        itemObj.get("testCaseId")?.asString?.let { registeredIds.add(it) }
                        itemObj.get("patientId")?.asString?.let { registeredIds.add(it) }
                    }
                }
                // If folder name looks like a UUID or ID and is not in registeredIds
                if (registeredIds.isNotEmpty() && !registeredIds.contains(case.name) && case.name != "shared") {
                    issues.add(
                        ValidationIssue(
                            severity = ValidationSeverity.WARNING,
                            code = "WARN_MADIE_MANIFEST_SYNC",
                            message = "Test case folder '${case.name}' is not registered in .madie metadata manifest.",
                            filePath = case.casePath.toString(),
                            line = 1,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            log.debug("Could not parse .madie file: {}", e.message)
        }
    }

    private fun checkRequiredFieldsForResource(
        res: RawResource,
        issues: MutableList<ValidationIssue>,
    ) {
        val obj = res.jsonObject
        val type = res.resourceType
        val id = res.id ?: "unknown"
        val prefix = if (res.jsonPathPrefix.isNotEmpty()) "${res.jsonPathPrefix}." else ""

        fun requireField(
            field: String,
            severity: ValidationSeverity,
            warningMsg: String? = null,
        ) {
            if (!obj.has(field) || obj.get(field).isJsonNull) {
                val msg = warningMsg ?: "$type/$id is missing mandatory field '$field'."
                issues.add(
                    ValidationIssue(
                        severity = severity,
                        code = if (severity == ValidationSeverity.ERROR) "ERR_MISSING_REQUIRED_FIELD" else "WARN_MISSING_REQUIRED_FIELD",
                        message = msg,
                        resourceType = type,
                        resourceId = id,
                        filePath = res.filePath.toString(),
                        jsonPath = "$prefix$field",
                        line = res.anchorLine,
                    ),
                )
            }
        }

        when (type) {
            "Condition" -> {
                requireField(
                    "clinicalStatus",
                    ValidationSeverity.WARNING,
                    "Condition/$id is missing 'clinicalStatus' (required by FHIR R4 and QICore; active condition CQL logic will evaluate to null/false).",
                )
            }
            "Encounter" -> {
                requireField("status", ValidationSeverity.ERROR)
                requireField(
                    "class",
                    ValidationSeverity.WARNING,
                    "Encounter/$id is missing 'class' (required by US Core and QICore).",
                )
            }
            "Observation" -> {
                requireField("status", ValidationSeverity.ERROR)
                requireField("code", ValidationSeverity.ERROR)
            }
            "Procedure" -> {
                requireField("status", ValidationSeverity.ERROR)
            }
            "MedicationRequest" -> {
                requireField("status", ValidationSeverity.ERROR)
                requireField("intent", ValidationSeverity.ERROR)
            }
            "Coverage" -> {
                requireField("status", ValidationSeverity.ERROR)
                requireField("beneficiary", ValidationSeverity.ERROR)
            }
        }
    }

    private fun extractReferenceTarget(
        obj: JsonObject,
        field: String,
    ): String? {
        if (!obj.has(field) || !obj.get(field).isJsonObject) return null
        val subObj = obj.getAsJsonObject(field)
        return subObj.get("reference")?.asString
    }

    private fun isPatientReference(ref: String): Boolean {
        if (ref.startsWith("Patient/")) return true
        if (ref.startsWith("urn:uuid:") || !ref.contains("/")) return true
        return false
    }

    private fun normalizeReferenceId(ref: String): String {
        val unversioned = ref.substringBefore("/_history/")
        return when {
            unversioned.startsWith("Patient/") -> unversioned.removePrefix("Patient/")
            unversioned.startsWith("urn:uuid:") -> unversioned.removePrefix("urn:uuid:")
            unversioned.contains("/") -> unversioned.substringAfterLast("/")
            else -> unversioned
        }
    }

    private fun isTestCasePatient(
        ref: String,
        patientIdentities: Set<String>,
    ): Boolean = ref in patientIdentities || normalizeReferenceId(ref) in patientIdentities

    private fun RawResource.fullUrlUuid(): String? = fullUrl?.takeIf { it.startsWith("urn:uuid:") }?.removePrefix("urn:uuid:")

    private fun isIgnoredReference(ref: String): Boolean {
        if (ref.isBlank()) return true
        if (ref.startsWith("http://") || ref.startsWith("https://") || ref.startsWith("urn:oid:")) return true
        return false
    }

    private fun collectReferences(
        element: JsonElement,
        currentPath: String,
        results: MutableList<Pair<String, String>>,
    ) {
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            for ((key, value) in obj.entrySet()) {
                val nextPath = if (currentPath.isEmpty()) key else "$currentPath.$key"
                if (key == "reference" && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    results.add(Pair(nextPath, value.asString))
                } else {
                    collectReferences(value, nextPath, results)
                }
            }
        } else if (element.isJsonArray) {
            val arr = element.asJsonArray
            for (i in 0 until arr.size()) {
                collectReferences(arr.get(i), "$currentPath[$i]", results)
            }
        }
    }

    /**
     * Returns the 1-based line of the first match on or after [startIndex] (0-based), falling back to
     * the first match in the whole file (e.g. a bundle entry's fullUrl sits above its resource's anchor).
     */
    private fun findLine(
        lines: List<String>,
        regex: Regex,
        startIndex: Int = 0,
    ): Int? = findLineFrom(lines, regex, startIndex) ?: if (startIndex > 0) findLineFrom(lines, regex, 0) else null

    private fun findLineFrom(
        lines: List<String>,
        regex: Regex,
        startIndex: Int,
    ): Int? {
        for (i in startIndex.coerceAtLeast(0) until lines.size) {
            if (regex.containsMatchIn(lines[i])) return i + 1
        }
        return null
    }
}
