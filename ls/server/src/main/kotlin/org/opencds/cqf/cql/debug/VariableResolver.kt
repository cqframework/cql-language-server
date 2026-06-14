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
import org.hl7.fhir.instance.model.api.IPrimitiveType
import org.hl7.fhir.r4.model.Period
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths

data class LocatorBounds(val startLine: Int, val startChar: Int, val endLine: Int, val endChar: Int)

class VariableResolver(
    private val fhirContext: FhirContext = FhirContext.forR4(),
    varRefs: MutableMap<Int, Any> = mutableMapOf(),
    varRefTypes: MutableMap<Int, String> = mutableMapOf(),
    nextVarRef: Int = 1000,
) {
    val varRefs: MutableMap<Int, Any> = varRefs
    val varRefTypes: MutableMap<Int, String> = varRefTypes
    var nextVarRef: Int = nextVarRef

    fun resetVarRefs() {
        varRefs.clear()
        varRefTypes.clear()
        nextVarRef = 1000
    }

    fun formatVariableValue(
        value: Any?,
        gson: Gson,
    ): String {
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
            else ->
                try {
                    gson.toJson(value)
                } catch (_: Exception) {
                    value.toString()
                }
        }
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

    fun isExpandable(value: Any?): Boolean {
        if (value == null) return false
        if (value is IPrimitiveType<*>) return false
        if (value is IBase) return true
        if (value is List<*> && value.isNotEmpty()) return true
        return false
    }

    fun registerIfExpandable(
        value: Any?,
        typeName: String? = null,
    ): Int {
        if (!isExpandable(value)) return 0
        val ref = nextVarRef++
        varRefs[ref] = value!!
        if (typeName != null) varRefTypes[ref] = typeName
        return ref
    }

    fun childrenOf(
        value: Any,
        typeName: String? = null,
        launchCompiler: CqlCompiler? = null,
    ): List<Variable> {
        val gson = Gson()
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
                        it.name = "[$index]"
                        it.value = formatVariableValue(item, gson)
                        it.variablesReference = registerIfExpandable(item)
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
        return if (trimmed.startsWith("list<", ignoreCase = true) && trimmed.endsWith(">")) {
            trimmed.removePrefix("list<").removeSuffix(">").trim()
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
                                val resourceType = resource.fhirType()
                                val idPart = resource.idElement?.idPart
                                val varName =
                                    if (!idPart.isNullOrEmpty()) {
                                        "$resourceType/$idPart"
                                    } else {
                                        file.fileName.toString().removeSuffix(".json").removeSuffix(".xml")
                                    }
                                testCaseList.add(
                                    Variable().also {
                                        it.name = varName
                                        it.value = formatVariableValue(resource, gson)
                                        it.type = resourceType
                                        it.variablesReference = registerIfExpandable(resource)
                                    },
                                )
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
