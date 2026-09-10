package org.opencds.cqf.cql.debug

import org.cqframework.cql.cql2elm.CqlCompiler
import org.cqframework.cql.cql2elm.CqlCompilerException
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
import org.slf4j.LoggerFactory
import java.net.URI

class EvaluateHelper(
    private val variableResolver: VariableResolver,
    private val compilationManager: CqlCompilationManager?,
) {
    companion object {
        private val log = LoggerFactory.getLogger(EvaluateHelper::class.java)
    }

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
    ): EvaluateResponse? {
        return when (category) {
            is CursorCategory.AliasReference -> {
                val rv = handler.runtimeRegistry.find(category.name)
                if (rv != null) {
                    EvaluateResponse().also {
                        it.result = variableResolver.formatVariableValue(rv.value)
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
                        it.result = variableResolver.formatVariableValue(rv.value)
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
                        it.result = variableResolver.formatVariableValue(rv.value)
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
                        it.result = variableResolver.formatVariableValue(rv.value)
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
        streamingLaunchUri: String?,
        variableTypeMap: Map<String, String>,
        launchParameters: List<ParameterRequestData>?,
        parameterMetadata: Map<String, List<CqlDebugServer.ParameterMetadata>>,
    ): EvaluateResponse {
        val registry = handler.runtimeRegistry

        val registryResult = registry.find(expression)
        if (registryResult != null) {
            return EvaluateResponse().also {
                it.result = variableResolver.formatVariableValue(registryResult.value)
                it.variablesReference = variableResolver.registerIfExpandable(registryResult.value)
            }
        }

        // Quoted/delimited identifiers (e.g. "IndexPCP", `My Define`) are a valid CQL form — the
        // quotes are delimiters, not part of the name, so look up the inner name exactly.
        val quotedRootResult =
            variableResolver.parseIdentifier(expression)
                ?.takeIf { it.rootDelimiter != null && it.propertySegments.isEmpty() }
                ?.let { parsed -> registry.find(parsed.rootName) }
        if (quotedRootResult != null) {
            return EvaluateResponse().also {
                it.result = variableResolver.formatVariableValue(quotedRootResult.value)
                it.variablesReference = variableResolver.registerIfExpandable(quotedRootResult.value)
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
                    it.result = variableResolver.formatVariableValue(cachedResult.value)
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
                            resolveFromCursorCategory(category, state, handler)
                        if (classifiedResult != null) {
                            return classifiedResult
                        }
                    }
                    val value = handler.findValueAtPosition(line, col)
                    if (value != null) {
                        return EvaluateResponse().also {
                            it.result = variableResolver.formatVariableValue(value)
                            it.variablesReference = variableResolver.registerIfExpandable(value)
                        }
                    }
                    val pausedElm = handler.lastPausedElm
                    if (pausedElm is Property) {
                        val propertyResult =
                            resolvePropertyValue(pausedElm, handler)
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

        val dottedResult = resolveDottedExpression(expression, registry)
        if (dottedResult != null) return dottedResult

        val varRefResult = variableResolver.findInVarRefs(expression)
        if (varRefResult != null) return varRefResult

        val missingResult = resolveMissingIdentifier(expression, registry)
        if (missingResult != null) return missingResult
        return notAvailable()
    }

    /**
     * Produces a helpful response when an identifier-shaped expression cannot be resolved.
     *
     * CQL is case-sensitive, so this never auto-resolves a mistyped identifier; it only reports
     * feedback:
     *  - root not found, exactly one case-insensitive match → `CQL identifiers are case-sensitive;
     *    did you mean <properly-cased path>?` (full corrected dotted path). Suggestion quotes are
     *    only rendered when the user typed a quoted/delimited identifier, preserving their form.
     *  - root not found, zero or ambiguous matches → `Identifier doesn't exist (CQL is case-sensitive)`.
     *  - root found exactly but a dotted property segment fails → suggest the case-correct property
     *    name from the value's canonical children; falls back to null (→ "not available") when no
     *    unique case-insensitive child matches.
     * Non-identifier expressions (spaces, operators, @-positions) return null so callers keep the
     * existing "not available" behavior.
     */
    fun resolveMissingIdentifier(
        expression: String,
        registry: RuntimeValueRegistry,
    ): EvaluateResponse? {
        val parsed = variableResolver.parseIdentifier(expression) ?: return null
        if (parsed.rootName.isEmpty()) return null
        if (parsed.propertySegments.isEmpty()) {
            return missingRootResponse(parsed, registry)
        }

        // Dotted expression: if the root already resolves exactly, a failed property segment is a
        // property case/availability issue, not a missing identifier.
        val rootValue = registry.find(parsed.rootName)?.value
        if (rootValue != null) {
            val correctedPath = caseCorrectedPath(rootValue, parsed)
            if (correctedPath != null) {
                return messageResponse(
                    "CQL identifiers are case-sensitive; did you mean $correctedPath?",
                )
            }
            log.debug(
                "resolveMissingIdentifier: root '{}' found but no case-correct property suggested for {}",
                parsed.rootName,
                expression,
            )
            return null
        }

        return missingRootResponse(parsed, registry)
    }

    private fun missingRootResponse(
        parsed: VariableResolver.IdentifierParts,
        registry: RuntimeValueRegistry,
    ): EvaluateResponse? {
        val candidates = registry.caseInsensitiveCandidates(parsed.rootName)
        log.debug(
            "resolveMissingIdentifier: rootName={} candidates={} expression={}",
            parsed.rootName,
            candidates,
        )
        val message =
            when {
                candidates.size == 1 -> {
                    val delimiter = parsed.rootDelimiter
                    val identifier =
                        candidates[0] + (if (delimiter == null && parsed.rootIndex != null) "[${parsed.rootIndex}]" else "")
                    val root = if (delimiter != null) "$delimiter$identifier$delimiter" else identifier
                    val suggested =
                        if (parsed.renderRest.isNotEmpty()) "$root.${parsed.renderRest}" else root
                    "CQL identifiers are case-sensitive; did you mean $suggested?"
                }
                else -> "Identifier doesn't exist (CQL is case-sensitive)"
            }
        return messageResponse(message)
    }

    private fun caseCorrectedPath(
        rootValue: Any?,
        parsed: VariableResolver.IdentifierParts,
    ): String? {
        var current = rootValue
        var index = 0
        val corrected = mutableListOf<String>()
        while (index < parsed.propertySegments.size) {
            val segment = parsed.propertySegments[index]
            val value = variableResolver.readProperty(current, segment)
            if (value != null) {
                corrected.add(segment)
                current = value
                index++
                continue
            }
            val matches = variableResolver.childrenNamesOf(current).filter { it.equals(segment, ignoreCase = true) }
            if (matches.size != 1 || matches[0] == segment) {
                return null
            }
            corrected.add(matches[0])
            corrected.addAll(parsed.propertySegments.drop(index + 1))
            return "${parsed.renderRoot}.${corrected.joinToString(".")}"
        }
        val correctedPath = corrected.joinToString(".")
        return if (correctedPath == parsed.propertySegments.joinToString(".")) {
            null
        } else {
            "${parsed.renderRoot}.$correctedPath"
        }
    }

    private fun messageResponse(message: String): EvaluateResponse =
        EvaluateResponse().also {
            it.result = message
            it.variablesReference = 0
        }

    fun resolvePropertyValue(
        property: Property,
        handler: StreamingBreakpointHandler,
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
                            "$id: ${variableResolver.formatPropertyValue(pv)}"
                        }
                    Pair("[$display]", sourceValue as List<*>)
                }
            }
            is IBase -> {
                val pv =
                    variableResolver.extractPropertyValue(sourceValue, propertyName)
                        ?: return null
                Pair(variableResolver.formatPropertyValue(pv), pv)
            }
            else -> null
        }
    }

    fun resolvePropertyFromAlias(
        aliasName: String,
        propertyName: String,
        handler: StreamingBreakpointHandler,
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
                            "$id: ${variableResolver.formatPropertyValue(pv)}"
                        }
                    Pair("[$display]", sourceValue as List<*>)
                }
            }
            is IBase -> {
                val pv =
                    variableResolver.extractPropertyValue(sourceValue, propertyName)
                        ?: return null
                Pair(variableResolver.formatPropertyValue(pv), pv)
            }
            else -> null
        }
    }

    /**
     * Compiles a typed CQL expression against the paused library and returns the compiled ELM
     * [FunctionDef] ready for evaluation, or an [EvaluateResponse] containing a compile-error
     * message.
     *
     * The expression is wrapped in a synthetic `define function "__debugEval__"(<aliases>): <expression>`.
     * A function (rather than a plain define) is used so that query-local aliases (e.g. `VTEStudy`
     * in a `from ... VTEStudy, where ...`) can be made visible: query aliases have no top-level
     * scope, so they are declared as function parameters and bound to their live runtime values at
     * evaluation time. With no aliases a zero-argument function is emitted.
     *
     * @param expression the raw CQL expression text typed by the user
     * @param sourceText the original CQL source of the paused document (from [CqlCompilationManager.getSourceText])
     * @param libraryManager the live session's [org.cqframework.cql.cql2elm.LibraryManager], already
     *   containing resolved includes/FHIRHelpers
     * @param aliases ordered `(identifier, concreteType)` pairs for the query aliases referenced by
     *   [expression], in signature order (which must match the argument order used at evaluation time).
     * @return a [Pair] of `(functionDef, null)` on success, or `(null, errorResponse)` on failure
     */
    fun evaluateAdHocExpression(
        expression: String,
        sourceText: String,
        libraryManager: org.cqframework.cql.cql2elm.LibraryManager,
        aliases: List<Pair<String, String>> = emptyList(),
    ): Pair<org.hl7.elm.r1.FunctionDef?, EvaluateResponse?> {
        val syntheticDef = syntheticFunctionDef(expression, aliases)
        // Log only the appended define (not the whole paused library source) at TRACE so the
        // default output channel stays lean; raising the CQL output channel to Trace restores it.
        if (log.isTraceEnabled()) {
            log.trace(
                "evaluateAdHocExpression: synthetic function ({} aliases, {} chars):\n{}",
                aliases.size,
                syntheticDef.length,
                syntheticDef,
            )
        }
        val syntheticCql = buildSyntheticDefine(sourceText, syntheticDef)

        val compiler = CqlCompiler(null, null, libraryManager)
        compiler.run(syntheticCql)

        if (CqlCompilerException.hasErrors(compiler.exceptions)) {
            val ex = compiler.exceptions.firstOrNull { it.severity == CqlCompilerException.ErrorSeverity.Error }
            val message = ex?.message ?: "Unknown compile error"
            val locator = ex?.locator
            val locStr =
                if (locator != null) {
                    " at ${locator.startLine}:${locator.startChar}"
                } else {
                    ""
                }
            // On compile failure surface the one actionable line: message + source locator.
            log.debug("evaluateAdHocExpression: compile error: {}{}", message, locStr)
            return null to messageResponse("Compile error: $message$locStr")
        }

        val library = compiler.library
        if (library == null) {
            log.debug("evaluateAdHocExpression: compiler.library is null after successful run()")
            return null to messageResponse("Compile error: no library produced")
        }

        val evalDef =
            library.statements?.def
                ?.filterIsInstance<org.hl7.elm.r1.FunctionDef>()
                ?.find { it.name == "__debugEval__" }
        if (evalDef == null) {
            log.debug("evaluateAdHocExpression: __debugEval__ function not found in compiled library")
            return null to messageResponse("Compile error: synthetic function not found")
        }

        val elmExpression = evalDef.expression
        if (elmExpression == null) {
            log.debug("evaluateAdHocExpression: __debugEval__ expression is null")
            return null to messageResponse("Compile error: empty expression")
        }

        log.debug(
            "evaluateAdHocExpression: success functionOperands={} expressionClass={}",
            evalDef.operand.map { it.name },
            elmExpression.javaClass.simpleName,
        )
        return evalDef to null
    }

    /** Builds the appended `define function "__debugEval__"(aliases): expression` block. */
    private fun syntheticFunctionDef(
        expression: String,
        aliases: List<Pair<String, String>>,
    ): String {
        val paramList =
            if (aliases.isEmpty()) {
                ""
            } else {
                aliases.joinToString(", ") { (name, type) ->
                    "\"$name\" ${normalizeType(type)}"
                }
            }
        return "define function \"__debugEval__\"($paramList): $expression"
    }

    /** Appends [syntheticDef] to the paused library source; CQL requires statements at EOL. */
    private fun buildSyntheticDefine(
        sourceText: String,
        syntheticDef: String,
    ): String = "${sourceText.trimEnd()}\n\n$syntheticDef\n"

    /**
     * Normalizes a translator-produced type string so it can be spliced into CQL source text as a
     * parameter type. `Trackable.resultType.toString()` yields lowercase generic keywords for
     * `ListType`/`IntervalType`/`ChoiceType`/`TupleType` (e.g. `list<...>`, `interval<...>`,
     * `choice<...>`, `tuple{...}`), but the CQL grammar's lexer is case-sensitive and requires the
     * capitalized keywords `List`/`Interval`/`Choice`/`Tuple` (intervals are `<...>`, tuples
     * `{...}` -- see cql.g4 typeSpecifier). All occurrences are replaced so nested generics like
     * `list<interval<System.DateTime>>` are fully normalized; already-capitalized strings pass
     * through unchanged. Class/Simple type names (e.g. `FHIR.DiagnosticReport`, `System.Quantity`)
     * are already valid as-is.
     *
     * `TupleTypeElement.toString()` also emits `name:type`, but cql.g4 defines
     * `tupleElementDefinition: referentialIdentifier typeSpecifier` (no colon), so [normalizeTupleColons]
     * rewrites each tuple element's colon separator to a space before splicing.
     */
    private fun normalizeType(type: String): String =
        normalizeTupleColons(
            type
                .replace("list<", "List<")
                .replace("interval<", "Interval<")
                .replace("choice<", "Choice<")
                .replace("tuple{", "Tuple{"),
        )

    /**
     * Rewrites each tuple element's `name:Type` (as emitted by `TupleTypeElement.toString()`) to
     * the grammar-valid `name Type`. DataType.toString uses dot-named class/simple types (e.g.
     * `FHIR.Patient`, `System.Interval...`) so the only colons in a type string are tuple element
     * separators. A stack records the angle-bracket depth at which each `Tuple{` was opened so a
     * `:` or `,` inside a nested generic element type (e.g. `Tuple{a Choice<X,Y>}`) is not mistaken
     * for an element separator.
     */
    private fun normalizeTupleColons(type: String): String {
        if ('{' !in type) return type
        val sb = StringBuilder(type.length)
        var angleDepth = 0
        val tupleOpenDepth = ArrayDeque<Int>()
        for (c in type) {
            when (c) {
                '<' -> {
                    angleDepth++
                    sb.append(c)
                }
                '>' -> {
                    angleDepth--
                    sb.append(c)
                }
                '{' -> {
                    tupleOpenDepth.addLast(angleDepth)
                    sb.append(c)
                }
                '}' -> {
                    tupleOpenDepth.removeLast()
                    sb.append(c)
                }
                ':' -> {
                    if (tupleOpenDepth.isNotEmpty() && angleDepth == tupleOpenDepth.last()) {
                        sb.append(' ')
                    } else {
                        sb.append(c)
                    }
                }
                ',' -> {
                    if (tupleOpenDepth.isNotEmpty() && angleDepth == tupleOpenDepth.last()) {
                        sb.append(", ")
                    } else {
                        sb.append(c)
                    }
                }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun notAvailable(): EvaluateResponse =
        EvaluateResponse().also {
            it.result = "not available"
            it.variablesReference = 0
        }

    /**
     * Resolves a dotted property path against a registered runtime value (e.g. "IndexPCP.period"
     * where "IndexPCP" is a stack variable/define/context resource holding a FHIR Encounter).
     * Navigates each segment through the runtime value with [VariableResolver.navigatePropertyPath]
     * and renders the result with [VariableResolver.formatPropertyValue] (so FHIR Periods display
     * as intervals, matching existing Debug Console conventions).
     */
    fun resolveDottedExpression(
        expression: String,
        registry: RuntimeValueRegistry,
    ): EvaluateResponse? {
        if (expression.startsWith("@")) return null
        val parsed = variableResolver.parseIdentifier(expression) ?: return null
        val propertySegments = parsed.propertySegments
        if (propertySegments.isEmpty()) return null
        val rootName = parsed.rootName
        val rootIndex = parsed.rootIndex
        val rest = propertySegments
        log.debug(
            "resolveDottedExpression: expression={} rootName={} rootIndex={} rest={}",
            expression,
            rootName,
            rootIndex,
            rest,
        )
        val rv = registry.find(rootName)
        if (rv == null) {
            log.debug(
                "resolveDottedExpression: root '{}' not found in registry (registered names={})",
                rootName,
                registry.displayNames(),
            )
            return null
        }
        log.debug(
            "resolveDottedExpression: root '{}' found (category={} type={})",
            rootName,
            rv.category,
            rv.type,
        )
        val path =
            buildList {
                if (rootIndex != null) add("[$rootIndex]")
                addAll(rest)
            }
        val value = variableResolver.navigatePropertyPath(rv.value, path)
        if (value == null) {
            log.debug(
                "resolveDottedExpression: property navigation for path={} returned null (valueClass={})",
                path,
                rv.value?.javaClass?.simpleName,
            )
            return null
        }
        log.debug(
            "resolveDottedExpression: path={} resolved to valueClass={}",
            path,
            value.javaClass.simpleName,
        )
        return EvaluateResponse().also {
            it.result = variableResolver.formatPropertyValue(value)
            it.variablesReference = variableResolver.registerIfExpandable(value)
        }
    }
}
