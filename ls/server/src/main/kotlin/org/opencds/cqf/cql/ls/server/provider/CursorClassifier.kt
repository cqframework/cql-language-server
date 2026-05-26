package org.opencds.cqf.cql.ls.server.provider

import org.antlr.v4.kotlinruntime.ParserRuleContext
import org.cqframework.cql.gen.cqlParser
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.opencds.cqf.cql.ls.server.visitor.CqlParseTreeVisitor

sealed interface CursorCategory {
    data class AliasDeclaration(val name: String, val range: Range) : CursorCategory

    data class AliasReference(val name: String, val range: Range) : CursorCategory

    data class LibraryAlias(val name: String, val range: Range) : CursorCategory

    data class PropertyName(val name: String, val aliasName: String?, val range: Range, val `implicit`: Boolean = false) : CursorCategory

    data class FunctionCall(val name: String, val libraryName: String?, val arity: Int?, val range: Range? = null) : CursorCategory

    data class ExpressionRef(val name: String, val libraryName: String?, val range: Range) : CursorCategory

    data class ParameterRef(val name: String, val libraryName: String?, val range: Range) : CursorCategory

    data class ExpressionDefName(val name: String, val range: Range) : CursorCategory

    data class FunctionDefName(val name: String, val range: Range) : CursorCategory

    data class ParameterDefName(val name: String, val range: Range) : CursorCategory

    data class OperandRef(val name: String, val range: Range) : CursorCategory

    data class Retrieve(val typeName: String, val modelQualifier: String?, val range: Range) : CursorCategory

    data class Literal(val kind: LiteralKind, val value: String, val range: Range) : CursorCategory

    data object KeywordSuppress : CursorCategory

    data object Unknown : CursorCategory
}

enum class LiteralKind { STRING, NUMBER, LONG_NUMBER, BOOLEAN, DATETIME, DATE, TIME, NULL, QUANTITY, RATIO }

object CursorClassifier {
    fun classify(
        parseTree: cqlParser.LibraryContext,
        position: Position,
    ): CursorCategory {
        val deepest =
            CqlParseTreeVisitor.findDeepestContext(parseTree, position)
                ?: return CursorCategory.Unknown

        // Keyword suppression: cursor on a query/with-without/expression-operator keyword.
        if (isKeywordSuppressPosition(deepest, position)) {
            return CursorCategory.KeywordSuppress
        }

        // Retrieve type: cursor on the type name in `[Encounter]` or `[FHIR.Encounter: "VS"]`.
        classifyRetrieveType(deepest)?.let { return it }

        if (deepest is cqlParser.IdentifierContext) {
            if (walkUpTo<cqlParser.AliasContext>(deepest) != null) {
                return CursorCategory.AliasDeclaration(
                    name = stripQuotes(deepest.text),
                    range = antlrTokenRange(deepest),
                )
            }
            // Let-clause identifier (e.g. `let X: N + 1`) is a local declaration.
            walkUpTo<cqlParser.LetClauseItemContext>(deepest)?.let { letCtx ->
                if (letCtx.identifier() === deepest) {
                    return CursorCategory.AliasDeclaration(
                        name = stripQuotes(deepest.text),
                        range = antlrTokenRange(deepest),
                    )
                }
            }
            val rawName = deepest.text

            // Sort-by implicit scope: identifiers in sort-by items reference
            // properties on the source element, not standalone expression refs.
            val sortByItem = walkUpTo<cqlParser.SortByItemContext>(deepest)
            if (sortByItem != null) {
                val miCtx = walkUpTo<cqlParser.MemberInvocationContext>(deepest)
                val queryCtx = walkUpTo<cqlParser.QueryContext>(sortByItem)
                if (queryCtx != null && miCtx != null) {
                    val refId = miCtx.referentialIdentifier()
                    val name = stripQuotes(refId.text)
                    val sourceAlias = extractSingleSourceAlias(queryCtx)
                    if (sourceAlias != null && !matchQueryAlias(miCtx, name)) {
                        return CursorCategory.PropertyName(
                            name = name,
                            aliasName = sourceAlias,
                            range = antlrTokenRange(miCtx),
                            implicit = true,
                        )
                    }
                }
            }

            // Query alias references (e.g., `E` in `E.period`): check before treating the
            // identifier as an expression definition name, so that alias references in
            // property accesses are correctly classified as AliasReference rather than
            // ExpressionDefName (which would require the ELM fallback to resolve).
            val aliasMiCtx = walkUpTo<cqlParser.MemberInvocationContext>(deepest)
            if (aliasMiCtx != null && matchQueryAlias(aliasMiCtx, stripQuotes(rawName))) {
                return CursorCategory.AliasReference(
                    name = stripQuotes(rawName),
                    range = antlrTokenRange(deepest),
                )
            }

            val expressionDefCtx = walkUpTo<cqlParser.ExpressionDefinitionContext>(deepest)
            val functionDefCtx = walkUpTo<cqlParser.FunctionDefinitionContext>(deepest)
            val parameterDefCtx = walkUpTo<cqlParser.ParameterDefinitionContext>(deepest)

            // Definition-site identifiers: must be the direct identifier child of the def,
            // not an identifier inside the body. Function bodies contain identifiers that
            // could otherwise be misclassified as the function name.
            if (expressionDefCtx != null && expressionDefCtx.identifier() === deepest) {
                return CursorCategory.ExpressionDefName(
                    name = stripQuotes(rawName),
                    range = antlrTokenRange(deepest),
                )
            }
            if (parameterDefCtx != null && parameterDefCtx.identifier() === deepest) {
                return CursorCategory.ParameterDefName(
                    name = stripQuotes(rawName),
                    range = antlrTokenRange(deepest),
                )
            }
            if (functionDefCtx != null) {
                // Function definition name: identifierOrFunctionIdentifier child of the def.
                val funcNameCtx = functionDefCtx.identifierOrFunctionIdentifier()
                if (encloses(funcNameCtx, deepest)) {
                    return CursorCategory.FunctionDefName(
                        name = stripQuotes(rawName),
                        range = antlrTokenRange(deepest),
                    )
                }
                // Operand parameter definition (e.g. `x` in `function Double(x Integer)`).
                if (functionDefCtx.operandDefinition().any { encloses(it.referentialIdentifier(), deepest) }) {
                    return CursorCategory.ParameterDefName(
                        name = stripQuotes(rawName),
                        range = antlrTokenRange(deepest),
                    )
                }
                // Identifier inside the function body matching an operand declaration → OperandRef.
                val operandName =
                    functionDefCtx.operandDefinition()
                        .map { stripQuotes(it.referentialIdentifier().text) }
                        .firstOrNull { it == stripQuotes(rawName) }
                if (operandName != null) {
                    return CursorCategory.OperandRef(
                        name = operandName,
                        range = antlrTokenRange(deepest),
                    )
                }
            }
        }

        val litCtx = walkUpTo<cqlParser.LiteralContext>(deepest)
        if (litCtx != null) {
            val kind =
                when (litCtx) {
                    is cqlParser.StringLiteralContext -> LiteralKind.STRING
                    is cqlParser.NumberLiteralContext -> LiteralKind.NUMBER
                    is cqlParser.LongNumberLiteralContext -> LiteralKind.LONG_NUMBER
                    is cqlParser.BooleanLiteralContext -> LiteralKind.BOOLEAN
                    is cqlParser.DateTimeLiteralContext -> LiteralKind.DATETIME
                    is cqlParser.DateLiteralContext -> LiteralKind.DATE
                    is cqlParser.TimeLiteralContext -> LiteralKind.TIME
                    is cqlParser.NullLiteralContext -> LiteralKind.NULL
                    is cqlParser.QuantityLiteralContext -> LiteralKind.QUANTITY
                    is cqlParser.RatioLiteralContext -> LiteralKind.RATIO
                    else -> null
                }
            if (kind != null) {
                return CursorCategory.Literal(kind = kind, value = litCtx.text, range = antlrTokenRange(litCtx))
            }
        }

        val qmiCtx = walkUpTo<cqlParser.QualifiedMemberInvocationContext>(deepest)
        if (qmiCtx != null) {
            val rawName =
                qmiCtx.referentialIdentifier()?.text
                    ?: return CursorCategory.Unknown
            val name = stripQuotes(rawName)
            val range =
                qmiCtx.referentialIdentifier()?.let { antlrTokenRange(it) }
                    ?: return CursorCategory.Unknown
            // Classify qualified member invocations: if the left side matches a library alias,
            // treat it as an ExpressionRef with libraryName. Otherwise it's a property access.
            val libraryName = resolveLibraryNameForQualifiedInvocation(qmiCtx, parseTree)
            if (libraryName != null) {
                return CursorCategory.ExpressionRef(name = name, libraryName = libraryName, range = range)
            }
            val aliasName = resolveAliasForProperty(qmiCtx)
            return CursorCategory.PropertyName(name = name, aliasName = aliasName, range = range)
        }

        val miCtx = walkUpTo<cqlParser.MemberInvocationContext>(deepest)
        if (miCtx != null) {
            val rawName =
                miCtx.referentialIdentifier()?.text
                    ?: return CursorCategory.Unknown
            val name = stripQuotes(rawName)
            return when {
                matchQueryAlias(miCtx, name) ->
                    CursorCategory.AliasReference(
                        name = name,
                        range = antlrTokenRange(miCtx),
                    )
                matchLibraryAlias(parseTree, name) ->
                    CursorCategory.LibraryAlias(
                        name = name,
                        range = antlrTokenRange(miCtx),
                    )
                else ->
                    CursorCategory.ExpressionRef(
                        name = name,
                        libraryName = null,
                        range = antlrTokenRange(miCtx),
                    )
            }
        }

        val qfiCtx = walkUpTo<cqlParser.QualifiedFunctionInvocationContext>(deepest)
        if (qfiCtx != null) {
            val rawName =
                qfiCtx.qualifiedFunction()
                    ?.identifierOrFunctionIdentifier()?.text
                    ?: return CursorCategory.Unknown
            val name = stripQuotes(rawName)
            val libraryName = resolveLibraryNameForQualifiedFunction(qfiCtx, parseTree)
            val range =
                qfiCtx.qualifiedFunction()
                    ?.identifierOrFunctionIdentifier()
                    ?.let { antlrTokenRange(it) }
            val arity = countArity(qfiCtx.qualifiedFunction()?.paramList())
            return CursorCategory.FunctionCall(name = name, libraryName = libraryName, arity = arity, range = range)
        }

        val fiCtx = walkUpTo<cqlParser.FunctionInvocationContext>(deepest)
        if (fiCtx != null) {
            val rawName =
                fiCtx.function()?.referentialIdentifier()?.text
                    ?: return CursorCategory.Unknown
            val name = stripQuotes(rawName)
            val range = fiCtx.function()?.referentialIdentifier()?.let { antlrTokenRange(it) }
            val arity = countArity(fiCtx.function()?.paramList())
            return CursorCategory.FunctionCall(name = name, libraryName = null, arity = arity, range = range)
        }

        // Codesystem identifier in a Code declaration (e.g. `Code '12345' from "SNOMEDCT"`).
        val csIdCtx = walkUpTo<cqlParser.CodesystemIdentifierContext>(deepest)
        if (csIdCtx != null) {
            val idCtx = csIdCtx.identifier()
            if (encloses(idCtx, deepest)) {
                val name = stripQuotes(idCtx.text)
                val libraryName = csIdCtx.libraryIdentifier()?.identifier()?.text?.let { stripQuotes(it) }
                return CursorCategory.ExpressionRef(name = name, libraryName = libraryName, range = antlrTokenRange(idCtx))
            }
        }
        // Code identifier in a Concept declaration (e.g. `Concept { "Code A" }`).
        val codeIdCtx = walkUpTo<cqlParser.CodeIdentifierContext>(deepest)
        if (codeIdCtx != null) {
            val idCtx = codeIdCtx.identifier()
            if (encloses(idCtx, deepest)) {
                val name = stripQuotes(idCtx.text)
                val libraryName = codeIdCtx.libraryIdentifier()?.identifier()?.text?.let { stripQuotes(it) }
                return CursorCategory.ExpressionRef(name = name, libraryName = libraryName, range = antlrTokenRange(idCtx))
            }
        }

        // QualifiedIdentifierExpression: a quoted/bare identifier used as a query source,
        // terminology, or other expression-name context (e.g. `from "Items" Item`,
        // `[Encounter: "Ambulatory Encounter"]`). Resolves to an expression/valueset/
        // codesystem/code/concept ref by name.
        val qieCtx = walkUpTo<cqlParser.QualifiedIdentifierExpressionContext>(deepest)
        if (qieCtx != null) {
            val refId = qieCtx.referentialIdentifier()
            if (encloses(refId, deepest)) {
                val name = stripQuotes(refId.text)
                val qualifiers = qieCtx.qualifierExpression()
                val libraryName =
                    if (qualifiers.isNotEmpty() && qualifiers.first().text in collectLibraryAliases(parseTree)) {
                        qualifiers.first().text
                    } else {
                        null
                    }
                return CursorCategory.ExpressionRef(name = name, libraryName = libraryName, range = antlrTokenRange(refId))
            }
        }

        // Definition-context fallback: cursor inside a top-level definition context but
        // not on any specific named slot (e.g. on the `define`/`function`/`parameter`
        // keyword, or on the `:`). Render the definition's hover for context.
        classifyDefinitionContextFallback(deepest)?.let { return it }

        return CursorCategory.Unknown
    }

    /**
     * When the cursor is somewhere inside an [ExpressionDefinitionContext] /
     * [FunctionDefinitionContext] / [ParameterDefinitionContext] but not in a slot
     * already handled by a more specific branch, treat it as a definition-name hover.
     * Matches the prior behavior of returning the enclosing definition's markup for
     * cursor positions on the `define` / `function` / `parameter` keywords.
     */
    private fun classifyDefinitionContextFallback(deepest: ParserRuleContext): CursorCategory? {
        val funcDef = walkUpTo<cqlParser.FunctionDefinitionContext>(deepest)
        if (funcDef != null) {
            val nameCtx = funcDef.identifierOrFunctionIdentifier()
            return CursorCategory.FunctionDefName(
                name = stripQuotes(nameCtx.text),
                range = antlrTokenRange(nameCtx),
            )
        }
        val exprDef = walkUpTo<cqlParser.ExpressionDefinitionContext>(deepest)
        if (exprDef != null) {
            val nameCtx = exprDef.identifier()
            return CursorCategory.ExpressionDefName(
                name = stripQuotes(nameCtx.text),
                range = antlrTokenRange(nameCtx),
            )
        }
        val paramDef = walkUpTo<cqlParser.ParameterDefinitionContext>(deepest)
        if (paramDef != null) {
            val nameCtx = paramDef.identifier()
            return CursorCategory.ParameterDefName(
                name = stripQuotes(nameCtx.text),
                range = antlrTokenRange(nameCtx),
            )
        }
        return null
    }

    private inline fun <reified T : ParserRuleContext> walkUpTo(ctx: ParserRuleContext): T? {
        var current: ParserRuleContext? = ctx
        while (current != null) {
            if (current is T) return current
            current = current.getParent() as? ParserRuleContext
        }
        return null
    }

    private fun collectQueryAliases(ctx: ParserRuleContext): Set<String> {
        val queryCtx = walkUpTo<cqlParser.QueryContext>(ctx) ?: return emptySet()
        val aliases = mutableSetOf<String>()

        val sourceClause = queryCtx.sourceClause()
        for (i in 0 until sourceClause.childCount) {
            val child = sourceClause.getChild(i)
            if (child is cqlParser.AliasedQuerySourceContext) {
                val alias = child.alias()?.identifier()?.text
                if (alias != null) aliases.add(alias)
            }
        }

        for (i in 0 until queryCtx.childCount) {
            val child = queryCtx.getChild(i)
            if (child is cqlParser.QueryInclusionClauseContext) {
                for (j in 0 until child.childCount) {
                    val ic = child.getChild(j)
                    val aqs =
                        when (ic) {
                            is cqlParser.WithClauseContext -> ic.aliasedQuerySource()
                            is cqlParser.WithoutClauseContext -> ic.aliasedQuerySource()
                            else -> null
                        }
                    if (aqs != null) {
                        val alias = aqs.alias()?.identifier()?.text
                        if (alias != null) aliases.add(alias)
                    }
                }
            }
        }

        return aliases
    }

    private fun matchQueryAlias(
        ctx: ParserRuleContext,
        name: String,
    ): Boolean {
        return name in collectQueryAliases(ctx)
    }

    /**
     * Finds the [AliasedQuerySourceContext] for the given [alias] within the query
     * that encloses [position]. Walks up from the deepest context at [position] to
     * the enclosing [QueryContext], then scans its [sourceClause] and all
     * [queryInclusionClause]s (with/without) for an alias matching [name].
     *
     * Returns null when not inside any query or no matching alias is found.
     */
    fun findAliasedQuerySource(
        parseTree: cqlParser.LibraryContext,
        position: Position,
        name: String,
    ): cqlParser.AliasedQuerySourceContext? {
        val deepest = CqlParseTreeVisitor.findDeepestContext(parseTree, position) ?: return null
        val queryCtx = walkUpTo<cqlParser.QueryContext>(deepest) ?: return null

        val sourceClause = queryCtx.sourceClause()
        for (i in 0 until sourceClause.childCount) {
            val child = sourceClause.getChild(i)
            if (child is cqlParser.AliasedQuerySourceContext) {
                val alias = child.alias()?.identifier()?.text
                if (alias == name) return child
            }
        }

        for (i in 0 until queryCtx.childCount) {
            val child = queryCtx.getChild(i)
            if (child is cqlParser.QueryInclusionClauseContext) {
                for (j in 0 until child.childCount) {
                    val ic = child.getChild(j)
                    val aqs =
                        when (ic) {
                            is cqlParser.WithClauseContext -> ic.aliasedQuerySource()
                            is cqlParser.WithoutClauseContext -> ic.aliasedQuerySource()
                            else -> null
                        }
                    if (aqs != null) {
                        val alias = aqs.alias()?.identifier()?.text
                        if (alias == name) return aqs
                    }
                }
            }
        }

        return null
    }

    private fun collectLibraryAliases(parseTree: cqlParser.LibraryContext): Set<String> {
        val aliases = mutableSetOf<String>()
        for (def in parseTree.definition()) {
            val incDef = def.includeDefinition() ?: continue
            val aliasCtx = incDef.localIdentifier()?.identifier() ?: continue
            aliases.add(aliasCtx.text)
        }
        return aliases
    }

    private fun matchLibraryAlias(
        parseTree: cqlParser.LibraryContext,
        name: String,
    ): Boolean {
        return name in collectLibraryAliases(parseTree)
    }

    /**
     * Extracts the alias of the single source from a query context.
     * Returns null for zero-source or multi-source queries.
     */
    private fun extractSingleSourceAlias(queryCtx: cqlParser.QueryContext): String? {
        val sourceClause = queryCtx.sourceClause() ?: return null
        val sources =
            (0 until sourceClause.childCount)
                .mapNotNull { sourceClause.getChild(it) as? cqlParser.AliasedQuerySourceContext }
        if (sources.size != 1) return null
        return sources.first().alias()?.identifier()?.text
    }

    /**
     * Resolves the library alias from the left side of a [QualifiedMemberInvocationContext].
     * For `Lib."Expr"`, returns "Lib" if it matches a known library alias, null otherwise.
     * For `Item.period` (where Item is a query alias, not a library alias), returns null.
     */
    private fun resolveLibraryNameForQualifiedInvocation(
        qmiCtx: cqlParser.QualifiedMemberInvocationContext,
        parseTree: cqlParser.LibraryContext,
    ): String? {
        val ieCtx = walkUpTo<cqlParser.InvocationExpressionTermContext>(qmiCtx) ?: return null
        val leftId = extractInvocationTargetIdentifier(ieCtx) ?: return null
        return if (leftId in collectLibraryAliases(parseTree)) leftId else null
    }

    /**
     * Resolves the library alias from the left side of a [QualifiedFunctionInvocationContext].
     * For `Lib.Func()`, returns "Lib" if it matches a known library alias, null otherwise.
     * For fluent function calls like `expr.Func()`, returns null.
     */
    private fun resolveLibraryNameForQualifiedFunction(
        qfiCtx: cqlParser.QualifiedFunctionInvocationContext,
        parseTree: cqlParser.LibraryContext,
    ): String? {
        val ieCtx = walkUpTo<cqlParser.InvocationExpressionTermContext>(qfiCtx) ?: return null
        val leftId = extractInvocationTargetIdentifier(ieCtx) ?: return null
        return if (leftId in collectLibraryAliases(parseTree)) leftId else null
    }

    /**
     * Extracts the simple identifier from the left side expression of an
     * [InvocationExpressionTermContext], given a simple identifier like a library alias.
     *
     * Handles the common case: `termExpressionTerm → invocationTerm → memberInvocation →
     * referentialIdentifier`. Returns null for any other expression structure.
     */
    private fun extractInvocationTargetIdentifier(ieCtx: cqlParser.InvocationExpressionTermContext): String? {
        val leftExpr = ieCtx.expressionTerm() ?: return null
        val termExpr = leftExpr as? cqlParser.TermExpressionTermContext ?: return null
        val term = termExpr.term() ?: return null
        val invocTerm = term as? cqlParser.InvocationTermContext ?: return null
        val invocation = invocTerm.invocation() ?: return null
        val memberInvoc = invocation as? cqlParser.MemberInvocationContext ?: return null
        return memberInvoc.referentialIdentifier()?.text
    }

    private fun resolveAliasForProperty(miCtx: cqlParser.QualifiedMemberInvocationContext): String? {
        val invTermCtx = walkUpTo<cqlParser.InvocationExpressionTermContext>(miCtx) ?: return null
        val baseTerm =
            (0 until invTermCtx.childCount)
                .mapNotNull { invTermCtx.getChild(it) as? cqlParser.ExpressionTermContext }
                .firstOrNull() ?: return null
        return extractIdentifierFromTerm(baseTerm)?.let { stripQuotes(it) }
    }

    /**
     * Extracts the identifier from a [cqlParser.ExpressionTermContext] by navigating
     * the type-safe parse tree hierarchy rather than positional child indices.
     *
     * Handles the common pattern:
     *   expressionTerm → termExpressionTerm → invocationTerm → memberInvocation → referentialIdentifier
     *
     * Returns null for any other expression structure.
     */
    private fun extractIdentifierFromTerm(term: ParserRuleContext): String? {
        val termExpr = term as? cqlParser.TermExpressionTermContext ?: return null
        val termNode = termExpr.term() ?: return null
        val invocTerm = termNode as? cqlParser.InvocationTermContext ?: return null
        val invocation = invocTerm.invocation() ?: return null
        val memberInvoc = invocation as? cqlParser.MemberInvocationContext ?: return null
        return memberInvoc.referentialIdentifier()?.text
    }

    /**
     * Strips surrounding double-quote characters from ANTLR identifier text.
     * ANTLR includes the delimiter quotes in DELIMITEDIDENTIFIER token text,
     * but the ELM stores names without surrounding quotes.
     */
    private fun stripQuotes(s: String): String = s.removeSurrounding("\"")

    /**
     * Returns true when the cursor is on a query/operator/with-without keyword token.
     * Folds the prior isQueryKeywordPosition / isExpressionKeywordPosition /
     * resolveWithWithoutHover keyword-region checks into a single test, so the
     * classifier can return KeywordSuppress directly.
     */
    private fun isKeywordSuppressPosition(
        deepest: ParserRuleContext,
        position: Position,
    ): Boolean {
        // With/Without clause: cursor on the leading keyword OR in the "such that" gap
        // between the aliased query source and the suchThat expression.
        var current: ParserRuleContext? = deepest
        while (current != null) {
            when (current) {
                is cqlParser.WithClauseContext -> return isWithKeywordRegion(current, current.aliasedQuerySource(), current.expression(), position)
                is cqlParser.WithoutClauseContext -> return isWithKeywordRegion(current, current.aliasedQuerySource(), current.expression(), position)
                is cqlParser.SourceClauseContext -> {
                    val kw = current.start ?: return false
                    if (kw.text != "from") return false
                    return isOnToken(kw, position)
                }
                is cqlParser.WhereClauseContext,
                is cqlParser.ReturnClauseContext,
                is cqlParser.LetClauseContext,
                is cqlParser.SortClauseContext,
                is cqlParser.AggregateClauseContext,
                -> {
                    val kw = current.start ?: return false
                    return isOnToken(kw, position)
                }
                is cqlParser.ExpressionContext,
                is cqlParser.ExpressionTermContext,
                is cqlParser.CaseExpressionItemContext,
                is cqlParser.IntervalOperatorPhraseContext,
                -> {
                    // ANTLR's deepestContext returns the expression(Term) when the cursor is
                    // on the operator token between two child expressions whose spans don't
                    // cover the operator position.
                    if (deepest == current) return true
                }
            }
            current = current.getParent() as? ParserRuleContext
        }
        return false
    }

    private fun isWithKeywordRegion(
        clause: ParserRuleContext,
        aqs: cqlParser.AliasedQuerySourceContext?,
        exprCtx: cqlParser.ExpressionContext?,
        position: Position,
    ): Boolean {
        if (aqs == null || exprCtx == null) return false
        val clauseStart = clause.start ?: return false
        // Cursor on the "with"/"without" keyword (before the aliasedQuerySource starts).
        val aqsStart = aqs.start ?: return false
        val onLeadingKw =
            (position.line == clauseStart.line - 1 && position.character >= clauseStart.charPositionInLine) &&
                (
                    position.line < aqsStart.line - 1 ||
                        (position.line == aqsStart.line - 1 && position.character < aqsStart.charPositionInLine)
                )
        if (onLeadingKw) return true
        // Cursor in the "such that" gap.
        val aqsStop = aqs.stop ?: return false
        val exprStart = exprCtx.start ?: return false
        val aqsEndLine = aqsStop.line - 1
        val aqsEndChar = aqsStop.charPositionInLine + (aqsStop.text?.length ?: 0)
        val exprStartLine = exprStart.line - 1
        val exprStartChar = exprStart.charPositionInLine
        val afterSource = position.line > aqsEndLine || (position.line == aqsEndLine && position.character >= aqsEndChar)
        val beforeExpr = position.line < exprStartLine || (position.line == exprStartLine && position.character < exprStartChar)
        return afterSource && beforeExpr
    }

    private fun isOnToken(
        token: org.antlr.v4.kotlinruntime.Token,
        position: Position,
    ): Boolean {
        val line = token.line - 1
        val start = token.charPositionInLine
        val end = start + (token.text?.length ?: 0)
        return position.line == line && position.character >= start && position.character < end
    }

    /**
     * Classifies a Retrieve type position: cursor on the type name in `[Encounter]` or
     * `[FHIR.Encounter: "VS"]`. Returns null when the cursor isn't on the type spec of
     * a Retrieve.
     */
    private fun classifyRetrieveType(deepest: ParserRuleContext): CursorCategory.Retrieve? {
        val ntsCtx = walkUpTo<cqlParser.NamedTypeSpecifierContext>(deepest) ?: return null
        val retrieveCtx = ntsCtx.getParent() as? cqlParser.RetrieveContext ?: return null
        val typeName = ntsCtx.referentialOrTypeNameIdentifier()?.text ?: return null
        val qualifiers = ntsCtx.qualifier()
        val modelQualifier = if (qualifiers.isNotEmpty()) qualifiers.joinToString(".") { it.text } else null
        return CursorCategory.Retrieve(
            typeName = stripQuotes(typeName),
            modelQualifier = modelQualifier,
            range = antlrTokenRange(retrieveCtx),
        )
    }

    private fun encloses(
        outer: ParserRuleContext?,
        inner: ParserRuleContext,
    ): Boolean {
        if (outer == null) return false
        var c: ParserRuleContext? = inner
        while (c != null) {
            if (c === outer) return true
            c = c.getParent() as? ParserRuleContext
        }
        return false
    }

    private fun countArity(paramList: cqlParser.ParamListContext?): Int {
        if (paramList == null) return 0
        return paramList.expression().size
    }

    private fun antlrTokenRange(ctx: ParserRuleContext): Range {
        val start = ctx.start ?: return Range(Position(0, 0), Position(0, 0))
        val stop =
            ctx.stop ?: return Range(
                Position(start.line - 1, start.charPositionInLine),
                Position(start.line - 1, start.charPositionInLine + (start.text?.length ?: 0)),
            )
        return Range(
            Position(start.line - 1, start.charPositionInLine),
            Position(stop.line - 1, stop.charPositionInLine + (stop.text?.length ?: 0)),
        )
    }
}
