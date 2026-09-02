package org.opencds.cqf.cql.debug

import ca.uhn.fhir.context.BaseRuntimeChildDefinition
import ca.uhn.fhir.context.BaseRuntimeElementDefinition
import ca.uhn.fhir.context.FhirContext
import com.google.gson.Gson
import org.cqframework.cql.cql2elm.CqlCompiler
import org.cqframework.cql.cql2elm.tracking.Trackable.resultType
import org.eclipse.lsp4j.debug.EvaluateResponse
import org.eclipse.lsp4j.debug.Variable
import org.hl7.cql.model.ClassType
import org.hl7.elm.r1.AggregateExpression
import org.hl7.elm.r1.AliasedQuerySource
import org.hl7.elm.r1.BinaryExpression
import org.hl7.elm.r1.Case
import org.hl7.elm.r1.Combine
import org.hl7.elm.r1.Element
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.First
import org.hl7.elm.r1.FunctionRef
import org.hl7.elm.r1.If
import org.hl7.elm.r1.Last
import org.hl7.elm.r1.LetClause
import org.hl7.elm.r1.NaryExpression
import org.hl7.elm.r1.Property
import org.hl7.elm.r1.Query
import org.hl7.elm.r1.Repeat
import org.hl7.elm.r1.Slice
import org.hl7.elm.r1.Sort
import org.hl7.elm.r1.TernaryExpression
import org.hl7.elm.r1.UnaryExpression
import org.hl7.fhir.instance.model.api.IBase
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.instance.model.api.IPrimitiveType
import org.hl7.fhir.r4.model.Period
import org.opencds.cqf.cql.engine.fhir.fhirModelNamespaceUri
import org.opencds.cqf.cql.engine.runtime.ClassInstance
import org.opencds.cqf.cql.engine.runtime.Interval
import org.opencds.cqf.cql.engine.runtime.StructuredValue
import org.opencds.cqf.cql.engine.runtime.Value
import org.opencds.cqf.fhir.cql.ClassInstanceHelper
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.opencds.cqf.cql.engine.runtime.List as CqlList

data class LocatorBounds(val startLine: Int, val startChar: Int, val endLine: Int, val endChar: Int)

class VariableResolver(
    private val fhirContext: FhirContext = FhirContext.forR4(),
    varRefs: ConcurrentHashMap<Int, Any> = ConcurrentHashMap(),
    varRefTypes: ConcurrentHashMap<Int, String> = ConcurrentHashMap(),
    nextVarRef: AtomicInteger = AtomicInteger(1000),
) {
    private companion object {
        val log = LoggerFactory.getLogger(VariableResolver::class.java)
    }

    val varRefs: ConcurrentHashMap<Int, Any> = varRefs
    val varRefTypes: ConcurrentHashMap<Int, String> = varRefTypes
    val nextVarRef: AtomicInteger = nextVarRef

    fun resetVarRefs() {
        varRefs.clear()
        varRefTypes.clear()
        nextVarRef.set(1000)
    }

    /**
     * Converts CQL-engine-only representations into the plain FHIR/Kotlin shapes the rest of this
     * class already knows how to render, so every scope (Locals, Resolved Defines, Parameters,
     * Test Case) displays FHIR resources identically:
     * - [CqlList] (the engine's Retrieve/list-literal wrapper, not a `kotlin.collections.List`) is
     *   unwrapped into a real list.
     * - [ClassInstance] (what a CQL Retrieve of a FHIR resource actually evaluates to — a generic
     *   structure of `Value`s, not a HAPI object) is converted back into a real FHIR resource via
     *   [ClassInstanceHelper], when possible, so it renders as full FHIR JSON like Test Case.
     * Values that aren't one of these (or that fail conversion) pass through unchanged.
     */
    fun normalizeValue(value: Any?): Any? =
        when (value) {
            is CqlList -> value.value.map { normalizeValue(it) }
            is ClassInstance -> resolveFhirResource(value) ?: value
            else -> value
        }

    private fun resolveFhirResource(value: ClassInstance): IBaseResource? {
        if (value.type.namespaceURI != fhirModelNamespaceUri) return null
        return try {
            ClassInstanceHelper.convertToFhirR4(value) as? IBaseResource
        } catch (_: Exception) {
            null
        }
    }

    fun formatVariableValue(
        rawValue: Any?,
        gson: Gson,
    ): String {
        val value = normalizeValue(rawValue)
        return when (value) {
            null -> "null"
            is String -> "\"$value\""
            is Boolean, is Number -> value.toString()
            is IPrimitiveType<*> -> value.getValueAsString() ?: "null"
            is IBase ->
                try {
                    fhirContext.newJsonParser().encodeToString(value)
                } catch (_: Exception) {
                    value.toString()
                }
            is Interval -> formatInterval(value, gson)
            is StructuredValue -> formatStructuredValue(value, gson)
            is Value -> value.toString()
            is List<*> ->
                if (value.isNotEmpty() && value.all { it is IBaseResource }) {
                    @Suppress("UNCHECKED_CAST")
                    formatResourceList(value as List<IBaseResource>)
                } else {
                    try {
                        gson.toJson(value)
                    } catch (_: Exception) {
                        value.toString()
                    }
                }
            else ->
                try {
                    gson.toJson(value)
                } catch (_: Exception) {
                    value.toString()
                }
        }
    }

    fun fhirResourceTypeOf(value: Any?): String? = (normalizeValue(value) as? IBaseResource)?.fhirType()

    fun formatResourceList(resources: List<IBaseResource>): String {
        if (resources.isEmpty()) return "[]"
        return "[" + resources.joinToString(", ") { "${it.fhirType()}/${getResourceId(it)}" } + "]"
    }

    private fun formatInterval(
        value: Interval,
        gson: Gson,
    ): String {
        val low = value.low?.let { formatVariableValue(it, gson) } ?: "null"
        val high = value.high?.let { formatVariableValue(it, gson) } ?: "null"
        val openBracket = if (value.lowClosed) "[" else "("
        val closeBracket = if (value.highClosed) "]" else ")"
        return "$openBracket$low, $high$closeBracket"
    }

    private fun formatStructuredValue(
        value: StructuredValue,
        gson: Gson,
    ): String {
        val fields =
            value.elements.entries.sortedBy { it.key }.joinToString(", ") { (name, v) ->
                "$name: ${formatVariableValue(v, gson)}"
            }
        return "${value.javaClass.simpleName} { $fields }"
    }

    fun formatPropertyValue(
        value: Any?,
        gson: Gson,
    ): String {
        if (value is Period) {
            return formatPeriodAsInterval(value)
        }
        return formatVariableValue(value, gson)
    }

    fun formatPeriodAsInterval(period: Period): String {
        val gson = Gson()
        val start = period.start?.let { formatVariableValue(it, gson) } ?: "null"
        val end = period.end?.let { formatVariableValue(it, gson) } ?: "null"
        return "[$start, $end)"
    }

    fun isExpandable(rawValue: Any?): Boolean {
        val value = normalizeValue(rawValue)
        if (value == null) return false
        if (value is IPrimitiveType<*>) return false
        if (value is IBase) return true
        if (value is List<*> && value.isNotEmpty()) return true
        if (value is Interval) return true
        if (value is StructuredValue) return true
        return false
    }

    fun registerIfExpandable(
        rawValue: Any?,
        typeName: String? = null,
    ): Int {
        val value = normalizeValue(rawValue)
        if (!isExpandable(value)) return 0
        val ref = nextVarRef.getAndIncrement()
        varRefs[ref] = value!!
        if (typeName != null) varRefTypes[ref] = typeName
        return ref
    }

    fun childrenOf(
        rawValue: Any,
        typeName: String? = null,
        launchCompiler: CqlCompiler? = null,
    ): List<Variable> {
        val gson = Gson()
        val value = normalizeValue(rawValue) ?: rawValue
        return when (value) {
            is IBase -> {
                if (value is IPrimitiveType<*>) {
                    return emptyList()
                }
                val elementDef =
                    fhirContext.getElementDefinition(value.javaClass) as? BaseRuntimeElementDefinition<*>
                if (elementDef != null) {
                    val children: List<BaseRuntimeChildDefinition> =
                        if (typeName != null) {
                            profileChildrenOf(typeName, elementDef, launchCompiler)
                        } else {
                            elementDef.children ?: emptyList()
                        }
                    children.flatMap { child ->
                        val accessor = child.getAccessor()
                        val childValues: List<IBase> =
                            try {
                                @Suppress("UNCHECKED_CAST")
                                accessor.getValues(value) as? List<IBase> ?: emptyList()
                            } catch (_: Exception) {
                                emptyList()
                            }
                        val childName = child.elementName
                        if (childValues.size == 1) {
                            listOf(
                                Variable().also {
                                    it.name = childName
                                    it.value = formatVariableValue(childValues[0], gson)
                                    it.variablesReference = registerIfExpandable(childValues[0])
                                },
                            )
                        } else {
                            childValues.mapIndexed { index, childValue ->
                                Variable().also {
                                    it.name = "$childName[$index]"
                                    it.value = formatVariableValue(childValue, gson)
                                    it.variablesReference = registerIfExpandable(childValue)
                                }
                            }
                        }
                    }
                } else {
                    emptyList()
                }
            }
            is List<*> -> {
                value.mapIndexed { index, item ->
                    Variable().also {
                        it.name =
                            if (item is IBaseResource) {
                                "${item.fhirType()}/${getResourceId(item)}"
                            } else {
                                "[$index]"
                            }
                        it.value = formatVariableValue(item, gson)
                        it.type = fhirResourceTypeOf(item)
                        it.variablesReference = registerIfExpandable(item)
                    }
                }
            }
            is Interval -> {
                listOf(
                    Variable().also {
                        it.name = "low"
                        it.value = value.low?.let { low -> formatVariableValue(low, gson) } ?: "null"
                        it.variablesReference = registerIfExpandable(value.low)
                    },
                    Variable().also {
                        it.name = "lowClosed"
                        it.value = value.lowClosed.toString()
                    },
                    Variable().also {
                        it.name = "high"
                        it.value = value.high?.let { high -> formatVariableValue(high, gson) } ?: "null"
                        it.variablesReference = registerIfExpandable(value.high)
                    },
                    Variable().also {
                        it.name = "highClosed"
                        it.value = value.highClosed.toString()
                    },
                )
            }
            is StructuredValue -> {
                value.elements.entries.sortedBy { it.key }.map { (name, elementValue) ->
                    Variable().also {
                        it.name = name
                        it.value = formatVariableValue(elementValue, gson)
                        it.variablesReference = registerIfExpandable(elementValue)
                    }
                }
            }
            else -> emptyList()
        }
    }

    fun findInVarRefs(name: String): EvaluateResponse? {
        for ((ref, value) in varRefs) {
            val result = findInVarRefsChildren(name, value, varRefTypes[ref])
            if (result != null) return result
        }
        return null
    }

    private fun findInVarRefsChildren(
        name: String,
        value: Any,
        typeName: String?,
    ): EvaluateResponse? {
        val children = childrenOf(value, typeName)
        for (child in children) {
            if (child.name == name) {
                return EvaluateResponse().also {
                    it.result = child.value
                    it.variablesReference = child.variablesReference
                }
            }
            if (child.variablesReference > 0) {
                val childValue = varRefs[child.variablesReference] ?: continue
                val childType = varRefTypes[child.variablesReference]
                val result = findInVarRefsChildren(name, childValue, childType)
                if (result != null) return result
            }
        }
        return null
    }

    fun extractPropertyValue(
        resource: IBase,
        propertyName: String,
    ): Any? {
        val capitalized = propertyName.replaceFirstChar { it.uppercase() }
        return try {
            val elementGetter = resource.javaClass.getMethod("get${capitalized}Element")
            elementGetter.invoke(resource)
        } catch (_: Exception) {
            try {
                val getter = resource.javaClass.getMethod("get$capitalized")
                getter.invoke(resource)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun navigatePropertyPath(
        root: Any?,
        segments: List<String>,
    ): Any? {
        log.debug("navigatePropertyPath: root={} segments={}", root?.javaClass?.simpleName, segments)
        var current = root
        for (segment in segments) {
            log.debug(
                "navigatePropertyPath: segment='{}' current={}",
                segment,
                current?.javaClass?.simpleName,
            )
            current = readProperty(current, segment)
            if (current == null) {
                log.debug("navigatePropertyPath: segment='{}' resolved to null, aborting", segment)
                return null
            }
        }
        log.debug(
            "navigatePropertyPath: resolved to valueClass={}",
            current?.javaClass?.simpleName,
        )
        return current
    }

    fun readProperty(
        rawValue: Any?,
        propertyName: String,
    ): Any? {
        val value = normalizeValue(rawValue) ?: return null
        return when (value) {
            is IBase -> {
                if (value is IPrimitiveType<*>) {
                    null
                } else {
                    val (base, index) = parseSegment(propertyName)
                    if (index != null && base.isEmpty()) {
                        value
                    } else {
                        val extracted = extractPropertyValue(value, base)
                        if (index != null) (extracted as? List<*>)?.getOrNull(index) else extracted
                    }
                }
            }
            is List<*> -> readListProperty(value, propertyName)
            is Interval -> readIntervalProperty(value, propertyName)
            is StructuredValue -> value.getElement(propertyName)
            else -> null
        }
    }

    private fun readListProperty(
        list: List<*>,
        propertyName: String,
    ): Any? {
        val (base, index) = parseSegment(propertyName)
        if (base.isEmpty()) return list.getOrNull(index ?: return null)
        if (index != null) {
            val item = list.getOrNull(index) ?: return null
            return (normalizeValue(item) as? IBase)?.let { extractPropertyValue(it, base) }
        }
        val values =
            list.mapNotNull { item ->
                (normalizeValue(item) as? IBase)?.let { extractPropertyValue(it, base) }
            }
        return if (values.isEmpty()) null else values
    }

    fun parseSegment(segment: String): Pair<String, Int?> {
        if (!segment.endsWith("]")) return Pair(segment, null)
        val open = segment.indexOf('[')
        if (open < 0) return Pair(segment, null)
        val index = segment.substring(open + 1, segment.length - 1).toIntOrNull()
        return Pair(segment.substring(0, open), index)
    }

    /**
     * A parsed identifier-shaped Debug Console expression.
     *
     * CQL identifiers are case-sensitive and may be simple (`IndexPCP`), quoted (`"Index PCP"`),
     * or delimited (`` `Index PCP` ``); quotes/backticks are delimiters, not part of the name. A
     * dotted path splits on qualifier dots that appear *outside* delimiters so quoted identifiers
     * containing dots (e.g. `"A.B".period`) are not mis-split.
     */
    data class IdentifierParts(
        val rootName: String,
        val rootDelimiter: String?,
        val rootIndex: Int?,
        /** Property segments after the root, in original casing, delimiters stripped. */
        val propertySegments: List<String>,
        /** Original encoded path used for suggestion rendering when no correction is needed. */
        val renderRoot: String,
        val renderRest: String,
    )

    /**
     * Parses an identifier-shaped expression into its root identifier and property segments.
     *
     * Returns null when the expression is not identifier-shaped (empty, at-position, or contains
     * characters like spaces/operators that simple identifiers cannot contain and that are not
     * wrapped in quotes/backticks).
     */
    fun parseIdentifier(expression: String): IdentifierParts? {
        val trimmed = expression.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("@")) return null

        var rootStart = 0
        var propertyStart = -1
        var delimiter: String? = null
        var rootEnd = trimmed.length
        if (trimmed.startsWith("\"") || trimmed.startsWith("`")) {
            val d = trimmed[0].toString()
            val closeIndex = trimmed.indexOf(d, 1)
            if (closeIndex < 0) return null
            delimiter = d
            rootStart = 1
            rootEnd = closeIndex
            propertyStart = trimmed.indexOf('.', closeIndex + 1)
            if (propertyStart < 0) propertyStart = trimmed.length
        } else {
            propertyStart = trimmed.indexOf('.')
            if (propertyStart < 0) propertyStart = trimmed.length
            rootEnd = propertyStart
        }

        val (baseName, rootIndex) = parseSegment(trimmed.substring(rootStart, rootEnd))

        if (delimiter == null) {
            val rawRoot = trimmed.substring(rootStart, rootEnd)
            val rootShapeOk = rawRoot.all { it.isLetter() || it.isDigit() || it == '_' || it == '[' || it == ']' }
            if (!rootShapeOk || rawRoot.none { it.isLetterOrDigit() }) return null
        }

        val restRaw =
            if (propertyStart in 1 until trimmed.length) {
                trimmed.substring(propertyStart + 1)
            } else {
                ""
            }
        if (delimiter == null && restRaw.isNotEmpty()) {
            val shapeOk =
                restRaw.all { it.isLetter() || it.isDigit() || it == '_' || it == '.' || it == '[' || it == ']' }
            if (!shapeOk || restRaw.none { it.isLetterOrDigit() }) return null
        }

        val propertySegments =
            if (restRaw.isEmpty()) {
                emptyList()
            } else {
                restRaw.split('.').filter { it.isNotEmpty() }.map { segment ->
                    when {
                        segment.startsWith("\"") && segment.endsWith("\"") && segment.length > 1 ->
                            segment.substring(1, segment.length - 1)
                        segment.startsWith("`") && segment.endsWith("`") && segment.length > 1 ->
                            segment.substring(1, segment.length - 1)
                        else -> segment
                    }
                }
            }

        val renderRoot = if (delimiter != null) "$delimiter${trimmed.substring(rootStart, rootEnd)}$delimiter" else trimmed.substring(rootStart, rootEnd)
        return IdentifierParts(
            rootName = baseName,
            rootDelimiter = delimiter,
            rootIndex = rootIndex,
            propertySegments = propertySegments,
            renderRoot = renderRoot,
            renderRest = restRaw,
        )
    }

    /**
     * Canonical child/property names of a value, for case-correcting dotted-path suggestions.
     * Side-effect free (does not expand or register varRefs).
     */
    fun childrenNamesOf(rawValue: Any?): List<String> {
        val value = normalizeValue(rawValue) ?: return emptyList()
        return when (value) {
            is IBase -> {
                if (value is IPrimitiveType<*>) {
                    emptyList()
                } else {
                    (fhirContext.getElementDefinition(value.javaClass) as? BaseRuntimeElementDefinition<*>)
                        ?.children
                        ?.map { it.elementName }
                        ?: emptyList()
                }
            }
            is List<*> -> value.flatMap { childrenNamesOf(normalizeValue(it)) }.distinct()
            is StructuredValue -> value.elements.keys.toList()
            is Interval -> listOf("low", "high", "lowClosed", "highClosed")
            else -> emptyList()
        }
    }

    private fun readIntervalProperty(
        interval: Interval,
        propertyName: String,
    ): Any? =
        when (propertyName) {
            "low" -> interval.low
            "high" -> interval.high
            "lowClosed" -> interval.lowClosed
            "highClosed" -> interval.highClosed
            else -> null
        }

    fun getResourceId(resource: IBase): String {
        return try {
            val idMethod = resource.javaClass.getMethod("getIdElement")
            val idElement = idMethod.invoke(resource)
            val idPartMethod = idElement.javaClass.getMethod("getIdPart")
            idPartMethod.invoke(idElement) as? String ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    fun getFhirContextForVersion(version: String?): FhirContext {
        return when (version?.uppercase()) {
            "DSTU3", "STU3" -> FhirContext.forDstu3()
            "R5" -> FhirContext.forR5()
            else -> fhirContext
        }
    }

    fun unwrapListType(typeName: String): String {
        val trimmed = typeName.trim()
        val lower = trimmed.lowercase()
        return if (lower.startsWith("list<") && lower.endsWith(">")) {
            trimmed.substring(5, trimmed.length - 1).trim()
        } else {
            trimmed
        }
    }

    fun profileChildrenOf(
        typeName: String,
        elementDef: BaseRuntimeElementDefinition<*>,
        launchCompiler: CqlCompiler? = null,
    ): List<BaseRuntimeChildDefinition> {
        val compiler = launchCompiler ?: return elementDef.children ?: emptyList()
        val modelManager = compiler.libraryManager?.modelManager ?: return elementDef.children ?: emptyList()
        val profileName = unwrapListType(typeName)
        val model =
            modelManager.globalCache.values.firstOrNull { it.resolveTypeName(profileName) != null }
                ?: return elementDef.children ?: emptyList()
        val classType =
            model.resolveTypeName(profileName) as? ClassType
                ?: return elementDef.children ?: emptyList()
        val allChildren = elementDef.children ?: emptyList()
        val profileMatched =
            classType.allElements.sortedBy { it.name }.mapNotNull { element ->
                allChildren.firstOrNull { it.elementName == element.name }
            }
        val matchedNames = profileMatched.map { it.elementName }.toSet()
        val unmatched = allChildren.filter { it.elementName !in matchedNames }
        return profileMatched + unmatched
    }

    fun buildVariableTypeMap(compiler: CqlCompiler?): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val library = compiler?.library ?: return map
        val defs = library.statements?.def ?: return map
        for (def in defs) {
            if (def.name != null && def.resultType != null) {
                map[def.name!!] = def.resultType.toString()
            }
            collectAliasTypes(def.expression, map)
        }
        return map
    }

    fun collectAliasTypes(
        elm: Element?,
        map: MutableMap<String, String>,
    ) {
        if (elm == null) return
        when (elm) {
            is AliasedQuerySource -> {
                if (elm.alias != null && elm.resultType != null) {
                    map[elm.alias!!] = elm.resultType.toString()
                }
                collectAliasTypes(elm.expression, map)
            }
            is LetClause -> {
                if (elm.identifier != null && elm.resultType != null) {
                    map[elm.identifier!!] = elm.resultType.toString()
                }
            }
            is Query -> {
                elm.source.forEach { collectAliasTypes(it, map) }
                elm.relationship.forEach { collectAliasTypes(it, map) }
                elm.let?.forEach { collectAliasTypes(it, map) }
            }
            is UnaryExpression -> collectAliasTypes(elm.operand, map)
            is BinaryExpression -> elm.operand.forEach { collectAliasTypes(it, map) }
            is TernaryExpression -> elm.operand.forEach { collectAliasTypes(it, map) }
            is NaryExpression -> elm.operand.forEach { collectAliasTypes(it, map) }
            is AggregateExpression -> collectAliasTypes(elm.source, map)
            is Last -> collectAliasTypes(elm.source, map)
            is First -> collectAliasTypes(elm.source, map)
            is If -> {
                collectAliasTypes(elm.then, map)
                collectAliasTypes(elm.`else`, map)
                collectAliasTypes(elm.condition, map)
            }
            is FunctionRef -> elm.operand.forEach { collectAliasTypes(it, map) }
            is Sort -> collectAliasTypes(elm.source, map)
            is Slice -> collectAliasTypes(elm.source, map)
            is Case -> {
                collectAliasTypes(elm.comparand, map)
                elm.caseItem?.forEach { item ->
                    collectAliasTypes(item.then, map)
                    collectAliasTypes(item.`when`, map)
                }
                collectAliasTypes(elm.`else`, map)
            }
            is Repeat -> {
                collectAliasTypes(elm.source, map)
                collectAliasTypes(elm.element, map)
            }
            is Property -> collectAliasTypes(elm.source, map)
            is Combine -> collectAliasTypes(elm.source, map)
        }
    }

    /**
     * Builds a single [Variable] for a FHIR resource, formatted identically regardless of which
     * scope (Locals, Resolved Defines, Parameters, Test Case) it is displayed in: name defaults to
     * "ResourceType/id", value is full FHIR JSON, type is the bare FHIR resource type, and expansion
     * always uses raw/unfiltered field order (no profile-based reordering).
     */
    fun buildResourceVariable(
        resource: IBaseResource,
        gson: Gson,
        displayNameOverride: String? = null,
    ): Variable {
        val resourceType = resource.fhirType()
        return Variable().also {
            it.name = displayNameOverride ?: "$resourceType/${getResourceId(resource)}"
            it.value = formatVariableValue(resource, gson)
            it.type = resourceType
            it.variablesReference = registerIfExpandable(resource)
        }
    }

    fun buildTestCaseVariables(
        testCaseUri: String?,
        fhirVersion: String?,
        testCasePath: java.nio.file.Path? = null,
    ): List<Variable> {
        val testCaseList = mutableListOf<Variable>()
        if (!testCaseUri.isNullOrEmpty()) {
            try {
                val resolvedPath = testCasePath ?: Paths.get(URI.create(testCaseUri))
                if (Files.exists(resolvedPath) && Files.isDirectory(resolvedPath)) {
                    val context = getFhirContextForVersion(fhirVersion)
                    val gson = Gson()
                    Files.newDirectoryStream(resolvedPath) { path ->
                        val name = path.fileName.toString().lowercase()
                        name.endsWith(".json") || name.endsWith(".xml")
                    }.use { stream ->
                        for (file in stream) {
                            try {
                                val content = Files.readString(file)
                                val fileName = file.fileName.toString().lowercase()
                                val parser =
                                    if (fileName.endsWith(".json")) context.newJsonParser() else context.newXmlParser()
                                val resource = parser.parseResource(content)
                                val idPart = resource.idElement?.idPart
                                val displayNameOverride =
                                    if (idPart.isNullOrEmpty()) {
                                        file.fileName.toString().removeSuffix(".json").removeSuffix(".xml")
                                    } else {
                                        null
                                    }
                                testCaseList.add(buildResourceVariable(resource, gson, displayNameOverride))
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        testCaseList.sortBy { it.name }
        return testCaseList
    }

    fun extractExpressionName(elm: Element?): String? {
        if (elm is ExpressionDef) return elm.name
        return elm?.javaClass?.simpleName
    }

    fun parseLocatorLines(locator: String?): LocatorBounds {
        if (locator == null) return LocatorBounds(0, 0, 0, 0)
        val parts = locator.split("-").takeIf { it.size == 2 } ?: return LocatorBounds(0, 0, 0, 0)
        val (sl, sc) =
            parts[0].split(":").takeIf { it.size == 2 }?.map { it.toIntOrNull() }
                ?: return LocatorBounds(0, 0, 0, 0)
        val (el, ec) =
            parts[1].split(":").takeIf { it.size == 2 }?.map { it.toIntOrNull() }
                ?: return LocatorBounds(0, 0, 0, 0)
        if (sl == null || sc == null || el == null || ec == null) return LocatorBounds(0, 0, 0, 0)
        return LocatorBounds(sl - 1, sc - 1, el - 1, ec)
    }

    fun notAvailable(): EvaluateResponse =
        EvaluateResponse().also {
            it.result = "not available"
            it.variablesReference = 0
        }
}
