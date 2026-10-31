package org.opencds.cqf.cql.ls.server.validation.testcase

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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

class TestCaseValidator {
    companion object {
        private val log = LoggerFactory.getLogger(TestCaseValidator::class.java)

        private val RESERVED_DIR_NAMES = setOf("results", "__MACOSX", ".git", ".cql-debug", "shared")

        fun normalizePath(pathStr: String): Path {
            return if (pathStr.startsWith("file:")) {
                try {
                    Paths.get(URI.create(pathStr.replace(" ", "%20")))
                } catch (e: Exception) {
                    val stripped = pathStr.removePrefix("file://").removePrefix("file:")
                    Paths.get(stripped)
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
    )

    data class DiscoveredCase(
        val name: String,
        val casePath: Path,
        val resources: List<RawResource>,
        val sharedResources: List<RawResource>,
        val madieManifestPath: Path? = null,
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
        val caseResults = discoveredCases.map { validateCase(it, options) }

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
        )
    }

    private fun discoverTestCases(
        targetPath: Path,
        options: ValidateTestCaseOptions,
    ): List<DiscoveredCase> {
        val cases = mutableListOf<DiscoveredCase>()

        if (targetPath.isRegularFile() && targetPath.name.endsWith(".json", ignoreCase = true)) {
            val resources = parseJsonFile(targetPath)
            if (resources.isNotEmpty()) {
                val isBundle = resources.size > 1 || resources.firstOrNull()?.jsonObject?.get("resourceType")?.asString == "Bundle"
                val shared = loadSharedResources(targetPath.parent, options)
                cases.add(
                    DiscoveredCase(
                        name = targetPath.name.removeSuffix(".json"),
                        casePath = targetPath,
                        resources = resources,
                        sharedResources = shared,
                    ),
                )
            }
            return cases
        }

        if (targetPath.isDirectory()) {
            // Check if this directory directly contains resource JSON files
            val directJsonFiles = listJsonFiles(targetPath)
            val directResources = directJsonFiles.flatMap { parseJsonFile(it) }

            if (directResources.isNotEmpty()) {
                // Single test case directory
                val shared = loadSharedResources(targetPath.parent, options)
                val madieFile = findMadieManifest(targetPath.parent)
                cases.add(
                    DiscoveredCase(
                        name = targetPath.name,
                        casePath = targetPath,
                        resources = directResources,
                        sharedResources = shared,
                        madieManifestPath = madieFile,
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
                            val files = listJsonFiles(subDir)
                            val subResources = files.flatMap { parseJsonFile(it) }
                            if (subResources.isNotEmpty()) {
                                cases.add(
                                    DiscoveredCase(
                                        name = subDir.name,
                                        casePath = subDir,
                                        resources = subResources,
                                        sharedResources = shared,
                                        madieManifestPath = madieFile,
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
                listJsonFiles(sharedDir).forEach { sharedList.addAll(parseJsonFile(it)) }
            }
        }
        options.sharedDirs?.forEach { sharedPathStr ->
            val p = normalizePath(sharedPathStr)
            if (Files.isDirectory(p)) {
                listJsonFiles(p).forEach { sharedList.addAll(parseJsonFile(it)) }
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

    private fun parseJsonFile(filePath: Path): List<RawResource> {
        return try {
            val text = filePath.readText()
            val lines = text.lines()
            val parsed = JsonParser.parseString(text)
            if (!parsed.isJsonObject) return emptyList()

            val obj = parsed.asJsonObject
            val type = obj.get("resourceType")?.asString ?: return emptyList()

            if (type == "Bundle") {
                val entries = obj.getAsJsonArray("entry") ?: return emptyList()
                val list = mutableListOf<RawResource>()
                for (i in 0 until entries.size()) {
                    val entry = entries.get(i)
                    if (entry.isJsonObject) {
                        val entryObj = entry.asJsonObject
                        val res = entryObj.getAsJsonObject("resource")
                        val fullUrl = entryObj.get("fullUrl")?.asString
                        if (res != null && res.has("resourceType")) {
                            list.add(
                                RawResource(
                                    resourceType = res.get("resourceType").asString,
                                    id = res.get("id")?.asString,
                                    fullUrl = fullUrl,
                                    filePath = filePath,
                                    jsonObject = res,
                                    lines = lines,
                                    jsonPathPrefix = "entry[$i].resource",
                                ),
                            )
                        }
                    }
                }
                list
            } else {
                listOf(
                    RawResource(
                        resourceType = type,
                        id = obj.get("id")?.asString,
                        fullUrl = null,
                        filePath = filePath,
                        jsonObject = obj,
                        lines = lines,
                    ),
                )
            }
        } catch (e: Exception) {
            log.warn("Failed to parse JSON file {}: {}", filePath, e.message)
            emptyList()
        }
    }

    private fun validateCase(
        case: DiscoveredCase,
        options: ValidateTestCaseOptions,
    ): TestCaseValidationResult {
        val issues = mutableListOf<ValidationIssue>()

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
                            line = findLine(patients.first().lines, Regex("\"resourceType\"\\s*:\\s*\"Patient\"")),
                        ),
                    )
                    patients.firstOrNull()?.id
                }
                else -> patients.first().id
            }

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
                                line = findLine(dup.lines, Regex("\"id\"\\s*:\\s*\"$id\"")),
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

                // Check subject, patient, beneficiary
                val subjectRef =
                    extractReferenceTarget(res.jsonObject, "subject")
                        ?: extractReferenceTarget(res.jsonObject, "patient")
                        ?: extractReferenceTarget(res.jsonObject, "beneficiary")

                if (subjectRef != null) {
                    val normalizedTargetId = normalizeReferenceId(subjectRef)
                    if (isPatientReference(subjectRef) && normalizedTargetId != patientId) {
                        issues.add(
                            ValidationIssue(
                                severity = ValidationSeverity.ERROR,
                                code = "ERR_PATIENT_MISMATCH",
                                message = "${res.resourceType}/${res.id ?: "unknown"} references Patient '$normalizedTargetId', but test case patient is '$patientId'.",
                                resourceType = res.resourceType,
                                resourceId = res.id,
                                filePath = res.filePath.toString(),
                                jsonPath = if (res.jsonPathPrefix.isNotEmpty()) "${res.jsonPathPrefix}.subject.reference" else "subject.reference",
                                line = findLine(res.lines, Regex("\"reference\"\\s*:\\s*\"[^\"]*$normalizedTargetId[^\"]*\"")),
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

                for ((jsonPath, refValue) in refs) {
                    if (isIgnoredReference(refValue)) continue

                    val resolved =
                        if (refValue.startsWith("urn:uuid:")) {
                            val uuid = refValue.removePrefix("urn:uuid:")
                            allUuids.contains(uuid) || fullUrls.contains(refValue)
                        } else if (refValue.contains("/")) {
                            val parts = refValue.split("/", limit = 2)
                            val refType = parts[0]
                            val refId = parts[1]
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
                                line = findLine(res.lines, Regex("\"reference\"\\s*:\\s*\"${Regex.escape(refValue)}\"")),
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
                    validateMeasureReport(res, patientId, issues)
                }

                // Check bundle fullUrl vs resource id
                if (res.fullUrl != null && res.id != null) {
                    val expectedEnding = "${res.resourceType}/${res.id}"
                    val expectedUrn = "urn:uuid:${res.id}"
                    if (!res.fullUrl.endsWith(expectedEnding) && res.fullUrl != expectedUrn && !res.fullUrl.endsWith("/${res.id}")) {
                        issues.add(
                            ValidationIssue(
                                severity = ValidationSeverity.WARNING,
                                code = "ERR_FULLURL_MISMATCH",
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
        )
    }

    private fun validateMeasureReport(
        res: RawResource,
        patientId: String?,
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
                                    val norm = normalizeReferenceId(subjectVal)
                                    if (norm != patientId) {
                                        issues.add(
                                            ValidationIssue(
                                                severity = ValidationSeverity.ERROR,
                                                code = "ERR_MEASURE_REPORT_SUBJECT",
                                                message = "MeasureReport contained subject parameter '$subjectVal' does not match test case patient ID '$patientId'.",
                                                resourceType = "MeasureReport",
                                                resourceId = res.id,
                                                filePath = res.filePath.toString(),
                                                jsonPath = "contained[Parameters].parameter[subject].valueString",
                                                line = findLine(res.lines, Regex("\"valueString\"\\s*:\\s*\"${Regex.escape(subjectVal)}\"")),
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
            val norm = normalizeReferenceId(topSubject)
            if (norm != patientId) {
                issues.add(
                    ValidationIssue(
                        severity = ValidationSeverity.ERROR,
                        code = "ERR_MEASURE_REPORT_SUBJECT",
                        message = "MeasureReport.subject '$topSubject' does not match test case patient ID '$patientId'.",
                        resourceType = "MeasureReport",
                        resourceId = res.id,
                        filePath = res.filePath.toString(),
                        jsonPath = "subject.reference",
                        line = findLine(res.lines, Regex("\"reference\"\\s*:\\s*\"[^\"]*${Regex.escape(norm)}[^\"]*\"")),
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
                        line = findLine(res.lines, Regex("\"resourceType\"\\s*:\\s*\"$type\"")) ?: 1,
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
        return when {
            ref.startsWith("Patient/") -> ref.removePrefix("Patient/")
            ref.startsWith("urn:uuid:") -> ref.removePrefix("urn:uuid:")
            ref.contains("/") -> ref.substringAfterLast("/")
            else -> ref
        }
    }

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

    private fun findLine(
        lines: List<String>,
        regex: Regex,
    ): Int? {
        val idx = lines.indexOfFirst { regex.containsMatchIn(it) }
        return if (idx >= 0) idx + 1 else null
    }
}
