package org.opencds.cqf.cql.debug

import com.google.gson.Gson
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.debug.EvaluateResponse
import org.hl7.elm.r1.Element
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.Property
import org.hl7.fhir.instance.model.api.IBase
import org.opencds.cqf.cql.engine.execution.State
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.provider.CursorCategory
import org.opencds.cqf.cql.ls.server.provider.CursorClassifier
import java.net.URI

class EvaluateHelper(
    private val variableResolver: VariableResolver,
    private val compilationManager: CqlCompilationManager?,
) {
    fun lookupByName(
        expression: String,
        frameId: Int?,
        snapshots: List<ExpressionSnapshot>,
        currentIndex: Int,
    ): EvaluateResponse {
        val candidates =
            if (frameId != null && frameId in snapshots.indices) {
                snapshots.subList(0, frameId + 1)
            } else {
                snapshots.take(currentIndex + 1)
            }
        return candidates.lastOrNull { nameMatches(it.name, expression) }
            ?.let { snap ->
                EvaluateResponse().also {
                    it.result = snap.value
                    it.variablesReference = 0
                }
            }
            ?: notAvailable()
    }

    fun handleHoverEvaluate(
        expression: String,
        frameId: Int?,
        snapshots: List<ExpressionSnapshot>,
        subExpressionSnapshots: List<SubExpressionSnapshot>,
    ): EvaluateResponse {
        val candidates =
            if (frameId != null && frameId in snapshots.indices) {
                snapshots.subList(0, frameId + 1)
            } else {
                snapshots
            }
        val defineSnapshot =
            candidates.lastOrNull { snap ->
                nameMatches(snap.name, expression)
            }
        if (defineSnapshot != null) {
            return EvaluateResponse().also {
                it.result = defineSnapshot.value
                it.variablesReference = 0
            }
        }

        if (expression.startsWith("@") && frameId != null && frameId in snapshots.indices) {
            val pos = parseHoverPosition(expression) ?: return notAvailable()
            val currentDefine = snapshots[frameId].name

            val match =
                subExpressionSnapshots
                    .filter { snap ->
                        snap.parentDefine == currentDefine && snap.contains(pos.first, pos.second)
                    }
                    .minByOrNull {
                        (it.endLine - it.startLine) * 10_000 + (it.endChar - it.startChar)
                    }

            if (match != null) {
                return EvaluateResponse().also {
                    it.result = match.value
                    it.variablesReference = 0
                }
            }
        }

        return notAvailable()
    }

    fun nameMatches(
        snapshotName: String,
        expression: String,
    ): Boolean {
        if (snapshotName == expression) return true
        val stripped = expression.trim('"')
        if (snapshotName == stripped) return true
        val words = snapshotName.split(" ").toSet()
        if (expression in words) return true
        if (stripped in words) return true
        return false
    }

    fun parseHoverPosition(expression: String): Pair<Int, Int>? {
        val raw = expression.removePrefix("@")
        val parts = raw.split(":").mapNotNull { it.toIntOrNull() }
        return if (parts.size == 2) parts[0] to parts[1] else null
    }

    fun splitParameterName(fullName: String): Pair<String, String> {
        val dotIndex = fullName.indexOf('.')
        return if (dotIndex > 0) {
            fullName.substring(0, dotIndex) to fullName.substring(dotIndex + 1)
        } else {
            "(Global)" to fullName
        }
    }

    fun findParameterMetadata(
        libraryName: String,
        paramName: String,
        parameterMetadata: Map<String, List<CqlDebugServer.ParameterMetadata>>,
    ): CqlDebugServer.ParameterMetadata? {
        return parameterMetadata[libraryName]?.find { it.name == paramName }
    }

    fun findLaunchParameterType(
        paramName: String,
        launchParameters: List<ParameterRequestData>?,
    ): String? {
        return launchParameters?.find { it.parameterName == paramName }?.parameterType
    }

    fun extractExpressionName(elm: Element?): String? {
        if (elm is ExpressionDef) return elm.name
        return elm?.javaClass?.simpleName
    }

    fun resolveFromCursorCategory(
        category: CursorCategory,
        state: State,
        handler: StreamingBreakpointHandler,
        gson: Gson,
    ): EvaluateResponse? {
        return when (category) {
            is CursorCategory.AliasReference -> {
                val rv = handler.runtimeRegistry.find(category.name)
                if (rv != null) {
                    EvaluateResponse().also {
                        it.result = variableResolver.formatVariableValue(rv.value, gson)
                        it.variablesReference = variableResolver.registerIfExpandable(rv.value)
                    }
                } else {
                    null
                }
            }
            is CursorCategory.OperandRef -> {
                val rv = handler.runtimeRegistry.find(category.name)
                if (rv != null) {
                    EvaluateResponse().also {
                        it.result = variableResolver.formatVariableValue(rv.value, gson)
                        it.variablesReference = variableResolver.registerIfExpandable(rv.value)
                    }
                } else {
                    null
                }
            }
            is CursorCategory.ExpressionRef -> {
                val rv =
                    if (category.libraryName != null) {
                        handler.runtimeRegistry.find(category.name, category.libraryName)
                    } else {
                        handler.runtimeRegistry.find(category.name)
                    }
                if (rv != null) {
                    EvaluateResponse().also {
                        it.result = variableResolver.formatVariableValue(rv.value, gson)
                        it.variablesReference = variableResolver.registerIfExpandable(rv.value)
                    }
                } else {
                    null
                }
            }
            is CursorCategory.ParameterRef -> {
                val rv = handler.runtimeRegistry.find(category.name)
                if (rv != null) {
                    EvaluateResponse().also {
                        it.result = variableResolver.formatVariableValue(rv.value, gson)
                        it.variablesReference = variableResolver.registerIfExpandable(rv.value)
                    }
                } else {
                    null
                }
            }
            is CursorCategory.PropertyName -> {
                if (category.aliasName != null) {
                    val result =
                        resolvePropertyFromAlias(
                            category.aliasName,
                            category.name,
                            handler,
                            gson,
                        )
                    if (result != null) {
                        EvaluateResponse().also {
                            it.result = result.first
                            it.variablesReference = variableResolver.registerIfExpandable(result.second)
                        }
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
            else -> null
        }
    }

    fun evaluateStreaming(
        expression: String,
        state: State,
        handler: StreamingBreakpointHandler,
        gson: Gson,
        streamingLaunchUri: String?,
        variableTypeMap: Map<String, String>,
        launchParameters: List<ParameterRequestData>?,
        parameterMetadata: Map<String, List<CqlDebugServer.ParameterMetadata>>,
    ): EvaluateResponse {
        val registry = handler.runtimeRegistry

        val registryResult = registry.find(expression)
        if (registryResult != null) {
            return EvaluateResponse().also {
                it.result = variableResolver.formatVariableValue(registryResult.value, gson)
                it.variablesReference = variableResolver.registerIfExpandable(registryResult.value)
            }
        }

        val libId =
            state.getCurrentLibrary()?.identifier
                ?: streamingLaunchUri?.let { uriStr ->
                    try {
                        val uri = java.net.URI.create(uriStr)
                        compilationManager?.compile(uri)?.compiledLibrary?.library?.identifier
                    } catch (_: Exception) {
                        null
                    }
                }
                ?: handler.lastPausedElm?.locator?.let { _ ->
                    org.hl7.elm.r1.VersionedIdentifier().also { it.id = "TestLib" }
                }

        if (libId != null) {
            state.cache.setExpressionCaching(true)
            val cachedResult = state.cache.getCachedExpression(libId, expression)
            if (cachedResult != null) {
                return EvaluateResponse().also {
                    it.result = variableResolver.formatVariableValue(cachedResult.value, gson)
                    it.variablesReference = variableResolver.registerIfExpandable(cachedResult.value)
                }
            }
        }

        if (expression.startsWith("@")) {
            val pos = expression.removePrefix("@")
            val parts = pos.split(":")
            if (parts.size == 2) {
                val line = parts[0].toIntOrNull()
                val col = parts[1].toIntOrNull()
                if (line != null && col != null) {
                    val parseTree =
                        streamingLaunchUri?.let {
                            compilationManager?.getParseTree(URI.create(it))
                        }
                    if (parseTree != null) {
                        val hoverPos = Position(line, col)
                        val category = CursorClassifier.classify(parseTree, hoverPos)
                        val classifiedResult =
                            resolveFromCursorCategory(category, state, handler, gson)
                        if (classifiedResult != null) {
                            return classifiedResult
                        }
                    }
                    val value = handler.findValueAtPosition(line, col)
                    if (value != null) {
                        return EvaluateResponse().also {
                            it.result = variableResolver.formatVariableValue(value, gson)
                            it.variablesReference = variableResolver.registerIfExpandable(value)
                        }
                    }
                    val pausedElm = handler.lastPausedElm
                    if (pausedElm is Property) {
                        val propertyResult =
                            resolvePropertyValue(pausedElm, handler, gson)
                        if (propertyResult != null) {
                            return EvaluateResponse().also {
                                it.result = propertyResult.first
                                it.variablesReference =
                                    variableResolver.registerIfExpandable(propertyResult.second)
                            }
                        }
                    }
                    val name = handler.getPausedExpressionName()
                    if (name != null) {
                        return EvaluateResponse().also {
                            it.result = "evaluating: $name"
                            it.variablesReference = 0
                        }
                    }
                }
            }
        }

        val varRefResult = variableResolver.findInVarRefs(expression)
        if (varRefResult != null) return varRefResult
        return notAvailable()
    }

    fun resolvePropertyValue(
        property: Property,
        handler: StreamingBreakpointHandler,
        gson: Gson,
    ): Pair<String, Any?>? {
        val sourceRef = property.source as? org.hl7.elm.r1.ExpressionRef ?: return null
        val sourceName = sourceRef.name ?: return null
        val sourceLibrary = sourceRef.libraryName
        val propertyName = property.path ?: return null

        val sourceValue =
            handler.runtimeRegistry.find(sourceName, sourceLibrary)?.value
                ?: return null

        return when (sourceValue) {
            is List<*> -> {
                val pairs =
                    sourceValue.mapNotNull { item ->
                        if (item is IBase) {
                            val id = variableResolver.getResourceId(item)
                            val pv = variableResolver.extractPropertyValue(item, propertyName)
                            if (pv != null) id to pv else null
                        } else {
                            null
                        }
                    }
                if (pairs.isEmpty()) {
                    null
                } else {
                    val display =
                        pairs.joinToString(", ") { (id, pv) ->
                            "$id: ${variableResolver.formatPropertyValue(pv, gson)}"
                        }
                    Pair("[$display]", sourceValue as List<*>)
                }
            }
            is IBase -> {
                val pv =
                    variableResolver.extractPropertyValue(sourceValue, propertyName)
                        ?: return null
                Pair(variableResolver.formatPropertyValue(pv, gson), pv)
            }
            else -> null
        }
    }

    fun resolvePropertyFromAlias(
        aliasName: String,
        propertyName: String,
        handler: StreamingBreakpointHandler,
        gson: Gson,
    ): Pair<String, Any?>? {
        val sourceValue =
            handler.runtimeRegistry.find(aliasName)?.value
                ?: return null

        return when (sourceValue) {
            is List<*> -> {
                val pairs =
                    sourceValue.mapNotNull { item ->
                        if (item is IBase) {
                            val id = variableResolver.getResourceId(item)
                            val pv = variableResolver.extractPropertyValue(item, propertyName)
                            if (pv != null) id to pv else null
                        } else {
                            null
                        }
                    }
                if (pairs.isEmpty()) {
                    null
                } else {
                    val display =
                        pairs.joinToString(", ") { (id, pv) ->
                            "$id: ${variableResolver.formatPropertyValue(pv, gson)}"
                        }
                    Pair("[$display]", sourceValue as List<*>)
                }
            }
            is IBase -> {
                val pv =
                    variableResolver.extractPropertyValue(sourceValue, propertyName)
                        ?: return null
                Pair(variableResolver.formatPropertyValue(pv, gson), pv)
            }
            else -> null
        }
    }

    private fun notAvailable(): EvaluateResponse =
        EvaluateResponse().also {
            it.result = "not available"
            it.variablesReference = 0
        }
}
