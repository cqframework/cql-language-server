package org.opencds.cqf.cql.ls.server.provider

import org.antlr.v4.kotlinruntime.ParserRuleContext
import org.cqframework.cql.cql2elm.CqlCompiler
import org.cqframework.cql.cql2elm.model.Model
import org.cqframework.cql.cql2elm.tracking.Trackable.resultType
import org.cqframework.cql.gen.cqlParser
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.hl7.cql.model.ChoiceType
import org.hl7.cql.model.ClassType
import org.hl7.cql.model.DataType
import org.hl7.cql.model.ListType
import org.hl7.cql.model.TupleType
import org.hl7.elm.r1.AccessModifier
import org.hl7.elm.r1.AliasedQuerySource
import org.hl7.elm.r1.CodeDef
import org.hl7.elm.r1.CodeSystemDef
import org.hl7.elm.r1.ConceptDef
import org.hl7.elm.r1.Element
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.FunctionDef
import org.hl7.elm.r1.FunctionRef
import org.hl7.elm.r1.LetClause
import org.hl7.elm.r1.Library
import org.hl7.elm.r1.ParameterDef
import org.hl7.elm.r1.Query
import org.hl7.elm.r1.ValueSetDef
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.utility.Elements
import org.opencds.cqf.cql.ls.server.visitor.CqlParseTreeVisitor
import org.opencds.cqf.cql.ls.server.visitor.DefinitionTrackBackVisitor
import java.net.URI

class HoverProvider(
    private val compilationManager: CqlCompilationManager,
    private val contentService: ContentService,
) {
    fun hover(params: HoverParams): Hover? {
        val uri = Uris.parseOrNull(params.textDocument.uri) ?: return null
        val compiler = compilationManager.compile(uri) ?: return null
        val library = compiler.library ?: return null
        val parseTree = compilationManager.getParseTree(uri) ?: return null
        val category = CursorClassifier.classify(parseTree, params.position)
        return when (category) {
            is CursorCategory.KeywordSuppress -> null
            is CursorCategory.Unknown -> null
            is CursorCategory.AliasDeclaration ->
                hoverAlias(category.name, category.range, compiler, uri, library, params.position, parseTree)
            is CursorCategory.AliasReference ->
                hoverAlias(category.name, category.range, compiler, uri, library, params.position, parseTree)
            is CursorCategory.LibraryAlias ->
                markupForLibraryAlias(category.name, library)?.let { Hover(it, category.range) }
            is CursorCategory.PropertyName ->
                hoverProperty(category, compiler, library, params.position, parseTree)
            is CursorCategory.FunctionCall ->
                hoverFunctionCall(category, compiler, library, uri, params.position)
            is CursorCategory.ExpressionRef ->
                hoverExpressionRef(category, compiler, library, uri)
            is CursorCategory.ParameterRef ->
                hoverParameterRef(category.name, category.libraryName, category.range, library, uri)
            is CursorCategory.ExpressionDefName -> {
                val resolved = resolveExpressionByName(category.name, null, compiler, library, uri) ?: return null
                markup(resolved, localLibraryLabel(library))?.let { Hover(it, category.range) }
            }
            is CursorCategory.FunctionDefName -> {
                val resolved = resolveFunctionByName(category.name, null, compiler, library, uri) ?: return null
                markup(resolved, localLibraryLabel(library))?.let { Hover(it, category.range) }
            }
            is CursorCategory.ParameterDefName -> {
                val paramDef = library.parameters?.def?.firstOrNull { it.name == category.name } ?: return null
                val rt = paramDef.resultType ?: return null
                Hover(
                    cqlBlock("""parameter "${paramDef.name}": ${formatCqlType(rt.toString())}""", localLibraryLabel(library)),
                    category.range,
                )
            }
            is CursorCategory.OperandRef ->
                hoverOperandRef(category, parseTree, params.position, library)
            is CursorCategory.Retrieve ->
                hoverRetrieve(category, compiler, library)
            is CursorCategory.Literal -> {
                val typeName =
                    when (category.kind) {
                        LiteralKind.STRING -> "System.String"
                        LiteralKind.NUMBER -> if (category.value.contains('.')) "System.Decimal" else "System.Integer"
                        LiteralKind.LONG_NUMBER -> "System.Long"
                        LiteralKind.BOOLEAN -> "System.Boolean"
                        LiteralKind.DATETIME -> "System.DateTime"
                        LiteralKind.DATE -> "System.Date"
                        LiteralKind.TIME -> "System.Time"
                        LiteralKind.NULL -> "System.Any"
                        LiteralKind.QUANTITY -> "System.Quantity"
                        LiteralKind.RATIO -> "System.Ratio"
                    }
                Hover(cqlBlock(formatCqlType(typeName), localLibraryLabel(library)), category.range)
            }
        }
    }

    private fun hoverAlias(
        name: String,
        range: Range,
        compiler: CqlCompiler,
        uri: URI,
        library: Library,
        position: Position,
        parseTree: cqlParser.LibraryContext,
    ): Hover? {
        // ANTLR-first: resolve type from parse tree for common cases
        // (retrieve sources, identifier sources, parenthesized expressions).
        val antlrType = resolveAliasTypeFromAntlrWithContext(parseTree, position, name, library)
        if (antlrType != null) {
            // Use ANTLR result directly when qualified or when ELM cannot improve it.
            if (antlrType.contains('.')) {
                return Hover(
                    cqlBlock("""(alias) $name: ${formatCqlType(antlrType)}""", localLibraryLabel(library)),
                    range,
                )
            }
            // Unqualified: try ELM first for the fully-qualified type.
            val elmMarkup = markupForAlias(name, compiler, uri, library, position)
            if (elmMarkup != null) return Hover(elmMarkup, range)
            // ELM didn't have it — use the ANTLR unqualified name.
            return Hover(
                cqlBlock("""(alias) $name: ${formatCqlType(antlrType)}""", localLibraryLabel(library)),
                range,
            )
        }
        // ELM fallback for cases ANTLR can't reach
        // (let clauses, aliases inside aggregates like exists/Last).
        return markupForAlias(name, compiler, uri, library, position)?.let { Hover(it, range) }
    }

    private fun hoverFunctionCall(
        category: CursorCategory.FunctionCall,
        compiler: CqlCompiler,
        library: Library,
        uri: URI,
        position: Position,
    ): Hover? {
        val candidates: List<FunctionDef>
        val resolvedLibraryName: String?

        if (category.libraryName != null) {
            // Qualified function call (e.g., FL."Func"): resolve from the named library.
            candidates = resolveIncludedLibrary(category.libraryName, library, uri)
                ?.compiledLibrary?.resolveFunctionRef(category.name)?.toList() ?: emptyList()
            resolvedLibraryName = category.libraryName
        } else {
            // Unqualified function call: try local library first.
            val local = compiler.compiledLibrary?.resolveFunctionRef(category.name)?.toList() ?: emptyList()
            if (local.isNotEmpty()) {
                candidates = local
                resolvedLibraryName = null
            } else {
                // Use ELM trackback to find which included library the compiler resolved it to.
                val functionRef = findFunctionRefAtPosition(compiler, position)
                val libAlias = functionRef?.libraryName
                if (libAlias != null) {
                    candidates = resolveIncludedLibrary(libAlias, library, uri)
                        ?.compiledLibrary?.resolveFunctionRef(category.name)?.toList() ?: emptyList()
                    resolvedLibraryName = libAlias
                } else {
                    candidates = emptyList()
                    resolvedLibraryName = null
                }
            }
        }

        val resolved = pickOverload(candidates, category.arity) ?: return null
        val fromLibrary = fromLibraryForName(resolvedLibraryName, library)
        val markup = markup(resolved, fromLibrary) ?: return null
        return category.range?.let { Hover(markup, it) }
    }

    /**
     * Walks the ELM tree to find the [FunctionRef] whose locator covers the given [position].
     * Relies on the compiler's own function resolution — the returned [FunctionRef.libraryName]
     * identifies which included library (if any) the function was resolved from.
     */
    private fun findFunctionRefAtPosition(
        compiler: CqlCompiler,
        position: Position,
    ): FunctionRef? {
        val library = compiler.library ?: return null
        val visitor = DefinitionTrackBackVisitor()
        return visitor.visitLibrary(library, position) as? FunctionRef
    }

    private fun hoverExpressionRef(
        category: CursorCategory.ExpressionRef,
        compiler: CqlCompiler,
        library: Library,
        uri: URI,
    ): Hover? {
        val resolved = resolveSymbolByName(category.name, category.libraryName, compiler, library, uri) ?: return null
        val fromLibrary = fromLibraryForName(category.libraryName, library)
        val markup =
            when (resolved) {
                is ValueSetDef -> markupValueSet(resolved, fromLibrary)
                is CodeSystemDef -> markupCodeSystem(resolved, fromLibrary)
                is CodeDef -> markupCode(resolved, fromLibrary)
                is ConceptDef -> markupConcept(resolved, fromLibrary)
                is ExpressionDef -> markup(resolved, fromLibrary)
                is FunctionDef -> markup(resolved, fromLibrary)
                is ParameterDef -> {
                    val rt = resolved.resultType
                    if (rt != null) cqlBlock("""parameter "${resolved.name}": ${formatCqlType(rt.toString())}""", fromLibrary) else null
                }
                else -> null
            } ?: return null
        return Hover(markup, category.range)
    }

    private fun hoverParameterRef(
        name: String,
        libraryName: String?,
        range: Range,
        library: Library,
        uri: URI,
    ): Hover? {
        val paramDef =
            if (libraryName == null) {
                library.parameters?.def?.firstOrNull { it.name == name }
            } else {
                resolveIncludedLibrary(libraryName, library, uri)
                    ?.library?.parameters?.def?.firstOrNull { it.name == name }
            } ?: return null
        val rt = paramDef.resultType ?: return null
        return Hover(
            cqlBlock("""parameter "${paramDef.name}": ${formatCqlType(rt.toString())}""", fromLibraryForName(libraryName, library)),
            range,
        )
    }

    private fun hoverOperandRef(
        category: CursorCategory.OperandRef,
        parseTree: cqlParser.LibraryContext,
        position: Position,
        library: Library,
    ): Hover? {
        val funcDefCtx =
            CqlParseTreeVisitor.findDeepestContext(parseTree, position)
                ?.let { walkUpToFunctionDef(it) } ?: return null
        val funcName = stripQuotes(funcDefCtx.identifierOrFunctionIdentifier().text)
        val funcDef =
            library.statements?.def?.firstOrNull { it is FunctionDef && it.name == funcName } as? FunctionDef
                ?: return null
        val operand = funcDef.operand?.firstOrNull { it.name == category.name } ?: return null
        val typeName = operand.resultType?.toString() ?: operand.operandType?.localPart ?: return null
        return Hover(
            cqlBlock("""parameter "${operand.name}": ${formatCqlType(typeName)}""", localLibraryLabel(library)),
            category.range,
        )
    }

    private fun hoverRetrieve(
        category: CursorCategory.Retrieve,
        compiler: CqlCompiler,
        library: Library,
    ): Hover? {
        val modelManager = compiler.libraryManager?.modelManager ?: return null
        val model =
            if (category.modelQualifier != null) {
                modelManager.resolveModel(category.modelQualifier)
            } else {
                modelManager.globalCache.values.firstOrNull { it.resolveTypeName(category.typeName) != null }
            } ?: return null
        val resolved = model.resolveTypeName(category.typeName) ?: return null
        val listType = "list<$resolved>"
        return Hover(cqlBlock(formatCqlType(listType), localLibraryLabel(library)), category.range)
    }

    private fun pickOverload(
        candidates: List<FunctionDef>,
        arity: Int?,
    ): FunctionDef? {
        if (candidates.isEmpty()) return null
        if (arity == null) return candidates.first()
        return candidates.firstOrNull { (it.operand?.size ?: 0) == arity } ?: candidates.first()
    }

    private fun walkUpToFunctionDef(ctx: ParserRuleContext): cqlParser.FunctionDefinitionContext? {
        var c: ParserRuleContext? = ctx
        while (c != null) {
            if (c is cqlParser.FunctionDefinitionContext) return c
            c = c.getParent() as? ParserRuleContext
        }
        return null
    }

    private fun stripQuotes(s: String): String = s.removeSurrounding("\"")

    /**
     * Resolves hover for a property name position identified by [CursorClassifier].
     * Uses name-keyed ELM lookups (no positioned tree walk).
     */
    private fun hoverProperty(
        category: CursorCategory.PropertyName,
        compiler: CqlCompiler,
        library: Library,
        position: Position,
        parseTree: cqlParser.LibraryContext,
    ): Hover? {
        if (category.aliasName != null) {
            // 1. Query alias source (AliasedQuerySource/LetClause) — ANTLR-first, ELM fallback.
            hoverPropertyFromQueryAlias(category, compiler, library, position, parseTree)?.let { return it }
            // 2. Implicit scope (sort-by) — ANTLR + ModelManager.
            if (category.implicit) {
                hoverPropertyFromImplicitScope(category, compiler, library, parseTree, position)?.let { return it }
            }
            // 3. Expression-ref source (e.g. `"Most Recent Encounter".period`).
            hoverPropertyFromExpressionDef(category, library)?.let { return it }
            return null
        }
        // 4. Complex expression receiver (e.g. `(query).period`) — resolve via ELM trackback.
        return hoverPropertyFromExpressionReceiver(compiler, position, category)
    }

    /**
     * Resolves a PropertyName whose alias is a query alias (AliasedQuerySource / LetClause).
     * Uses an ANTLR-first approach: resolves the alias source type from the parse tree by
     * cursor position, walks the model's [ClassType] for the property element. Falls back
     * to ELM [findAliasSource] for complex alias sources ANTLR can't reach.
     */
    private fun hoverPropertyFromQueryAlias(
        category: CursorCategory.PropertyName,
        compiler: CqlCompiler,
        library: Library,
        position: Position,
        parseTree: cqlParser.LibraryContext,
    ): Hover? {
        val aliasName = category.aliasName ?: return null

        // ANTLR-first: resolve property type from parse tree by position.
        // Handles simple retrieve sources, expression-ref sources, and
        // parenthesized retrieves. Avoids ELM structural wrapper gaps (FunctionRef, etc.)
        // by using position-based parse tree navigation.
        val antlrType = resolvePropertyTypeFromAntlrAlias(parseTree, position, aliasName, category.name, compiler, library)
        if (antlrType != null) {
            return Hover(
                cqlBlock(
                    "(element) $aliasName.${category.name}: ${formatCqlType(antlrType.toString())}",
                    localLibraryLabel(library),
                ),
                category.range,
            )
        }

        // ELM fallback for complex alias sources ANTLR can't reach
        // (tuple types, choice types, function-return types, nested let clauses).
        val defs = library.statements?.def ?: return null
        val searchDefs = defs.filter { Elements.containsPosition(it, position) }.ifEmpty { defs }
        for (def in searchDefs) {
            val aqs = findAliasSource(def, aliasName) as? AliasedQuerySource ?: continue
            val sourceType = aqs.expression?.resultType ?: continue
            val elementType = if (sourceType is ListType) sourceType.elementType else sourceType
            val propType = resolvePropertyType(elementType, category.name) ?: continue
            return Hover(
                cqlBlock(
                    "(element) $aliasName.${category.name}: ${formatCqlType(propType.toString())}",
                    localLibraryLabel(library),
                ),
                category.range,
            )
        }
        return null
    }

    /**
     * Resolves a PropertyName whose source is an [ExpressionDef] (named expression as
     * the property base, e.g. `"Most Recent Encounter".period`). The alias name matches
     * a top-level define, not a query alias.
     */
    private fun hoverPropertyFromExpressionDef(
        category: CursorCategory.PropertyName,
        library: Library,
    ): Hover? {
        val aliasName = category.aliasName ?: return null
        val exprDef = library.statements?.def?.firstOrNull { it.name == aliasName } ?: return null
        val sourceType = exprDef.resultType ?: return null
        val elementType = if (sourceType is ListType) sourceType.elementType else sourceType
        val propType = resolvePropertyType(elementType, category.name) ?: return null
        return Hover(
            cqlBlock(
                """(element) "$aliasName".${category.name}: ${formatCqlType(propType.toString())}""",
                localLibraryLabel(library),
            ),
            category.range,
        )
    }

    /**
     * Implicit-scope properties (e.g. `sort by period`): the parse tree carries the alias
     * but the ELM has a Null expression. Resolve the alias source via ANTLR and walk
     * [ModelManager] to find the property type.
     */
    private fun hoverPropertyFromImplicitScope(
        category: CursorCategory.PropertyName,
        compiler: CqlCompiler,
        library: Library,
        parseTree: cqlParser.LibraryContext,
        position: Position,
    ): Hover? {
        val aliasName = category.aliasName ?: return null
        val aqs = CursorClassifier.findAliasedQuerySource(parseTree, position, aliasName) ?: return null
        val typeName = resolveAliasTypeFromAntlr(aqs, library) ?: return null
        val modelManager = compiler.libraryManager?.modelManager ?: return null
        val model = resolveModelForRetrieve(aqs, modelManager) ?: return null
        val sourceType = model.resolveTypeName(typeName)
        val elementType =
            if (sourceType is ClassType) {
                sourceType
            } else {
                (sourceType as? ListType)?.elementType as? ClassType
            } ?: return null
        val propType = resolvePropertyType(elementType, category.name) ?: return null
        return Hover(
            cqlBlock(
                "(element) ${category.aliasName}.${category.name}: ${formatCqlType(propType.toString())}",
                localLibraryLabel(library),
            ),
            category.range,
        )
    }

    /**
     * Resolves property hover for a complex expression receiver with no named alias
     * (e.g. `(query).property`). Uses ELM trackback to find the expression element at
     * the cursor position and display its compiled result type.
     */
    private fun hoverPropertyFromExpressionReceiver(
        compiler: CqlCompiler,
        position: Position,
        category: CursorCategory.PropertyName,
    ): Hover? {
        val library = compiler.library ?: return null
        val element = DefinitionTrackBackVisitor().visitLibrary(library, position) ?: return null
        val resultType = element.resultType?.toString() ?: return null
        return Hover(
            cqlBlock(
                "(element) ${category.name}: ${formatCqlType(resultType)}",
                localLibraryLabel(library),
            ),
            category.range,
        )
    }

    /**
     * Resolves a property type from a [DataType] by name, handling both
     * [ClassType.allElements] and [TupleType.elements].
     */
    fun resolvePropertyType(
        elementType: DataType,
        propertyName: String,
    ): DataType? {
        return when (elementType) {
            is ClassType ->
                elementType.allElements.firstOrNull { it.name == propertyName }?.type
                    ?: resolveChoiceProperty(elementType, propertyName)
            is TupleType -> elementType.elements.firstOrNull { it.name == propertyName }?.type
            is ChoiceType -> {
                val resolved = elementType.types.mapNotNull { resolvePropertyType(it, propertyName) }
                if (resolved.isEmpty()) null else ChoiceType(resolved)
            }
            else -> null
        }
    }

    /**
     * Resolves a choice property when the model info flattens choice elements
     * into expanded forms (e.g. `valueQuantity`, `valueCodeableConcept`) rather
     * than keeping a single `value` element of type `ChoiceType`.
     */
    private fun resolveChoiceProperty(
        classType: ClassType,
        propertyName: String,
    ): DataType? {
        val prefix = propertyName
        val matches =
            classType.allElements.filter {
                it.name.startsWith(prefix) && it.name.length > prefix.length &&
                    it.name[prefix.length].isUpperCase()
            }
        if (matches.isEmpty()) return null
        return ChoiceType(matches.map { it.type })
    }

    /**
     * Resolves a library alias (e.g., "AHA") to a compiled library by looking up the
     * IncludeDef in the current library, locating the file via ContentService, and compiling it.
     */
    private fun resolveIncludedLibrary(
        alias: String,
        library: Library,
        uri: URI,
    ): CqlCompiler? {
        val root = Uris.getHead(uri)
        val includeDef = library.includes?.def?.firstOrNull { it.localIdentifier == alias } ?: return null
        val identifier =
            VersionedIdentifier().apply {
                id = includeDef.path ?: return null
                version = includeDef.version
            }
        val targetUri = contentService.locate(root, identifier).firstOrNull() ?: return null
        return compilationManager.compile(targetUri)
    }

    /**
     * Returns a human-readable source label for a library alias, e.g.
     * "QICoreCommon version '1.0.000'". Returns null if the alias cannot be resolved.
     */
    private fun libraryLabel(
        alias: String,
        library: Library,
    ): String? {
        val inc = library.includes?.def?.firstOrNull { it.localIdentifier == alias } ?: return null
        val path = inc.path ?: return null
        val version = inc.version
        return if (version != null) "$path version '$version'" else path
    }

    private fun localLibraryLabel(library: Library): String? {
        val id = library.identifier ?: return null
        val name = id.id ?: return null
        val version = id.version
        return if (version != null) "$name version '$version'" else name
    }

    private fun accessPrefix(level: AccessModifier?): String =
        when (level) {
            AccessModifier.PRIVATE -> "private "
            else -> ""
        }

    private fun markupForAlias(
        alias: String,
        compiler: CqlCompiler,
        uri: URI,
        library: Library,
        position: Position? = null,
    ): MarkupContent? {
        val defs = library.statements?.def ?: return null
        val searchDefs =
            if (position != null) {
                defs.filter { Elements.containsPosition(it, position) }.ifEmpty { defs }
            } else {
                defs
            }
        for (def in searchDefs) {
            val found = findAliasSource(def, alias)
            if (found != null) {
                val rt = found.resultType ?: continue
                return cqlBlock("""(alias) $alias: ${formatCqlType(rt.toString())}""", localLibraryLabel(library))
            }
        }
        return null
    }

    /**
     * Walks an ELM subtree looking for an [AliasedQuerySource] or [LetClause] declared
     * with the given [alias]. Looks up by NAME, not by position — bridges an
     * ANTLR-identified alias to its ELM source so we can read its `resultType`.
     *
     * Recurses through structural wrappers ([Query], [ExpressionDef]) and through
     * operator expressions ([OperatorExpression]) so queries nested inside
     * aggregates like `Last(...)` or wrappers like `exists(...)` are reachable.
     */
    private fun findAliasSource(
        elm: Element?,
        alias: String,
    ): Element? {
        if (elm == null) return null
        return when (elm) {
            is AliasedQuerySource -> if (elm.alias == alias) elm else findAliasSource(elm.expression, alias)
            is LetClause -> if (elm.identifier == alias) elm else null
            is Query ->
                elm.source.firstNotNullOfOrNull { findAliasSource(it, alias) }
                    ?: elm.relationship.firstNotNullOfOrNull { findAliasSource(it, alias) }
                    ?: elm.let?.firstNotNullOfOrNull { findAliasSource(it, alias) }
            is ExpressionDef -> findAliasSource(elm.expression, alias)
            is org.hl7.elm.r1.UnaryExpression -> findAliasSource(elm.operand, alias)
            is org.hl7.elm.r1.BinaryExpression -> elm.operand.firstNotNullOfOrNull { findAliasSource(it, alias) }
            is org.hl7.elm.r1.TernaryExpression -> elm.operand.firstNotNullOfOrNull { findAliasSource(it, alias) }
            is org.hl7.elm.r1.NaryExpression -> elm.operand.firstNotNullOfOrNull { findAliasSource(it, alias) }
            is org.hl7.elm.r1.AggregateExpression -> findAliasSource(elm.source, alias)
            is org.hl7.elm.r1.Last -> findAliasSource(elm.source, alias)
            is org.hl7.elm.r1.First -> findAliasSource(elm.source, alias)
            is org.hl7.elm.r1.If ->
                findAliasSource(elm.then, alias)
                    ?: findAliasSource(elm.`else`, alias)
                    ?: findAliasSource(elm.condition, alias)
            is org.hl7.elm.r1.FunctionRef -> elm.operand.firstNotNullOfOrNull { findAliasSource(it, alias) }
            is org.hl7.elm.r1.Sort -> findAliasSource(elm.source, alias)
            is org.hl7.elm.r1.Slice -> findAliasSource(elm.source, alias)
            is org.hl7.elm.r1.Case ->
                findAliasSource(elm.comparand, alias)
                    ?: elm.caseItem.firstNotNullOfOrNull { item ->
                        findAliasSource(item.then, alias) ?: findAliasSource(item.`when`, alias)
                    }
                    ?: findAliasSource(elm.`else`, alias)
            is org.hl7.elm.r1.Repeat -> findAliasSource(elm.source, alias) ?: findAliasSource(elm.element, alias)
            is org.hl7.elm.r1.Property -> findAliasSource(elm.source, alias)
            is org.hl7.elm.r1.Combine -> findAliasSource(elm.source, alias)
            else -> null
        }
    }

    /**
     * Extracts the type name from a retrieve query source (e.g. `[Encounter]`)
     * using only the ANTLR parse tree. Returns null for non-retrieve sources.
     */
    private fun resolveRetrieveTypeFromAntlr(aqs: cqlParser.AliasedQuerySourceContext): String? {
        val retrieveCtx = aqs.querySource()?.retrieve() ?: return null
        val nts = retrieveCtx.namedTypeSpecifier() ?: return null
        val qualifiers = nts.qualifier().joinToString(".") { it.text }
        val typeName = nts.referentialOrTypeNameIdentifier()?.text ?: return null
        return if (qualifiers.isNotEmpty()) "$qualifiers.$typeName" else typeName
    }

    /**
     * Resolves the [Model] for a retrieve context. When the type specifier is
     * model-qualified (e.g. `[FHIR.Encounter]`), uses the model qualifier.
     * Otherwise, tries each loaded model to find one that can resolve the type.
     */
    private fun resolveModelForRetrieve(
        aqs: cqlParser.AliasedQuerySourceContext,
        modelManager: org.cqframework.cql.cql2elm.ModelManager?,
    ): Model? {
        if (modelManager == null) return null
        val retrieveCtx = aqs.querySource()?.retrieve() ?: return null
        val nts = retrieveCtx.namedTypeSpecifier() ?: return null
        val qualifiers = nts.qualifier().toList()
        if (qualifiers.isNotEmpty()) {
            return modelManager.resolveModel(qualifiers.first().text)
        }
        val typeName = nts.referentialOrTypeNameIdentifier()?.text ?: return null
        return modelManager.globalCache.values.firstOrNull { it.resolveTypeName(typeName) != null }
    }

    /**
     * Resolves the alias source type, handling the three possible [querySource]
     * alternatives:
     *   1. Retrieve sources (e.g. `[Encounter]`) — type extracted from ANTLR.
     *   2. Expression-ref sources (e.g. `"Qualifying Encounter..."`) — looked up
     *      by name in the ELM library.
     *   3. Parenthesized expressions (e.g. `( complex expression )`) — walks the
     *      ANTLR tree for a nested [RetrieveContext] at any depth; falls back to
     *      ELM alias lookup when no retrieve is found.
     */
    private fun resolveAliasTypeFromAntlr(
        aqs: cqlParser.AliasedQuerySourceContext,
        library: Library,
    ): String? {
        val retrieveType = resolveRetrieveTypeFromAntlr(aqs)
        if (retrieveType != null) return retrieveType

        val qie = aqs.querySource()?.qualifiedIdentifierExpression()
        if (qie != null) {
            val exprName = qie.text.removeSurrounding("\"")
            val def =
                library.statements?.def?.firstOrNull { it.name == exprName }
                    ?: return null
            return def.resultType?.toString()
        }

        // Parenthesized expression: walk ANTLR tree to find a retrieve at any depth
        // (handles common patterns like `([Retrieve])`, `([Retrieve].filter())`, etc.)
        val nestedRetrieve = findNestedRetrieve(aqs.querySource())
        if (nestedRetrieve != null) {
            val nts = nestedRetrieve.namedTypeSpecifier() ?: return null
            val qualifiers = nts.qualifier().joinToString(".") { it.text }
            val typeName = nts.referentialOrTypeNameIdentifier()?.text ?: return null
            return if (qualifiers.isNotEmpty()) "$qualifiers.$typeName" else typeName
        }

        // Fallback: bridge to ELM via alias name
        val aliasName = aqs.alias()?.identifier()?.text?.removeSurrounding("\"") ?: return null
        for (def in library.statements?.def.orEmpty()) {
            val found = findAliasSource(def, aliasName)
            if (found != null) return found.resultType?.toString()
        }
        return null
    }

    /**
     * Recursively walks an ANTLR subtree to find a [cqlParser.RetrieveContext] at
     * any depth. Handles parenthesized expressions, fluent invocation chains, and
     * other syntactic wrappers between the [querySource] node and the actual retrieve.
     */
    private fun findNestedRetrieve(ctx: ParserRuleContext?): cqlParser.RetrieveContext? {
        if (ctx == null) return null
        if (ctx is cqlParser.RetrieveContext) return ctx
        for (i in 0 until ctx.childCount) {
            val child = ctx.getChild(i)
            if (child is ParserRuleContext) {
                val found = findNestedRetrieve(child)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Resolves alias type from the ANTLR parse tree, given an alias name and position.
     * Wraps [resolveAliasTypeFromAntlr] with the [findAliasedQuerySource] lookup.
     * Returns null for non-retrieve or complex expression-ref sources.
     */
    private fun resolveAliasTypeFromAntlrWithContext(
        parseTree: cqlParser.LibraryContext,
        position: Position,
        aliasName: String,
        library: Library,
    ): String? {
        val aqs = CursorClassifier.findAliasedQuerySource(parseTree, position, aliasName) ?: return null
        return resolveAliasTypeFromAntlr(aqs, library)
    }

    /**
     * Resolves a property [DataType] for an alias using only the ANTLR parse tree
     * and [ModelManager]. Locates the [AliasedQuerySourceContext] at the cursor
     * [position] by walking up the parse tree, extracts the source type name from
     * the retrieve/expression-ref/paren source, resolves it via the model, then
     * looks up the [propertyName] element.
     *
     * Returns null for non-retrieve sources or when the model cannot resolve the type —
     * the caller should fall back to ELM-based resolution.
     */
    private fun resolvePropertyTypeFromAntlrAlias(
        parseTree: cqlParser.LibraryContext,
        position: Position,
        aliasName: String,
        propertyName: String,
        compiler: CqlCompiler,
        library: Library,
    ): DataType? {
        val aqs = CursorClassifier.findAliasedQuerySource(parseTree, position, aliasName) ?: return null
        val typeName = resolveAliasTypeFromAntlr(aqs, library) ?: return null
        val modelManager = compiler.libraryManager?.modelManager ?: return null
        val model = resolveModelForRetrieve(aqs, modelManager) ?: return null
        val sourceType = model.resolveTypeName(typeName) ?: return null
        val elementType = if (sourceType is ListType) sourceType.elementType else sourceType
        return resolvePropertyType(elementType, propertyName)
    }

    /**
     * Resolves a function definition by name and optional library alias.
     * Uses the compiled library's symbol table directly, bypassing position-based ELM lookup.
     * Returns null when the function is not found or the library cannot be resolved.
     */
    private fun resolveFunctionByName(
        name: String,
        libraryName: String?,
        compiler: CqlCompiler,
        library: Library,
        uri: URI,
    ): FunctionDef? {
        return if (libraryName == null) {
            compiler.compiledLibrary?.resolveFunctionRef(name)?.firstOrNull()
        } else {
            resolveIncludedLibrary(libraryName, library, uri)
                ?.compiledLibrary?.resolveFunctionRef(name)?.firstOrNull()
        }
    }

    /**
     * Resolves an expression definition by name and optional library alias.
     * Uses the compiled library's symbol table directly, bypassing position-based ELM lookup.
     * Returns null when the expression is not found or the library cannot be resolved.
     */
    private fun resolveExpressionByName(
        name: String,
        libraryName: String?,
        compiler: CqlCompiler,
        library: Library,
        uri: URI,
    ): ExpressionDef? {
        return if (libraryName == null) {
            try {
                compiler.compiledLibrary?.resolveExpressionRef(name)
            } catch (_: Exception) {
                null
            }
        } else {
            resolveIncludedLibrary(libraryName, library, uri)
                ?.compiledLibrary?.resolveExpressionRef(name)
        }
    }

    /**
     * Resolves a named symbol by trying all definition types in order.
     * Tries ExpressionDef, ValueSetDef, CodeSystemDef, CodeDef, ConceptDef,
     * FunctionDef in sequence. Returns null when not found.
     */
    private fun resolveSymbolByName(
        name: String,
        libraryName: String?,
        compiler: CqlCompiler,
        library: Library,
        uri: URI,
    ): Any? {
        val lib =
            if (libraryName == null) {
                compiler.compiledLibrary
            } else {
                resolveIncludedLibrary(libraryName, library, uri)?.compiledLibrary
            } ?: return null
        try {
            lib.resolveExpressionRef(name)?.let { return it }
        } catch (_: Exception) {
        }
        try {
            lib.resolveValueSetRef(name)?.let { return it }
        } catch (_: Exception) {
        }
        try {
            lib.resolveCodeSystemRef(name)?.let { return it }
        } catch (_: Exception) {
        }
        try {
            lib.resolveCodeRef(name)?.let { return it }
        } catch (_: Exception) {
        }
        try {
            lib.resolveConceptRef(name)?.let { return it }
        } catch (_: Exception) {
        }
        try {
            lib.resolveFunctionRef(name)?.firstOrNull()?.let { return it }
        } catch (_: Exception) {
        }
        // Also check parameters from the source library, not compiledLibrary
        val sourceLibrary = if (libraryName == null) library else resolveIncludedLibrary(libraryName, library, uri)?.library
        sourceLibrary?.parameters?.def?.firstOrNull { it.name == name }?.let { return it }
        return null
    }

    /**
     * Returns the human-readable library label for the given library alias.
     * When libraryName is null, returns the local library label.
     */
    private fun fromLibraryForName(
        libraryName: String?,
        library: Library,
    ): String? {
        return libraryName?.let { libraryLabel(it, library) } ?: localLibraryLabel(library)
    }

    private fun formatCqlType(type: String): String {
        val highlightedType =
            type
                .replace(Regex("\\blist<"), "List<")
                .replace(Regex("\\binterval<"), "Interval<")
                .replace(Regex("\\btuple\\{"), "Tuple{")
                .replace(Regex("\\bchoice<"), "Choice<")
        if (highlightedType.length <= 50) return highlightedType
        val tokens = smartTokenize(highlightedType)
        val sb = StringBuilder()
        var indent = 0
        val step = "  "
        for (i in tokens.indices) {
            val token = tokens[i]
            when (token) {
                "<", "{" -> {
                    sb.append(token).append("\n").append(step.repeat(++indent))
                }
                ">", "}" -> {
                    indent--
                    val next = if (i + 1 < tokens.size) tokens[i + 1] else ""
                    if (next == ",") {
                        sb.append(token)
                    } else {
                        sb.append("\n").append(step.repeat(indent)).append(token)
                    }
                }
                "," -> {
                    sb.append(token).append("\n").append(step.repeat(indent))
                }
                else -> sb.append(token)
            }
        }
        return sb.toString()
    }

    private fun smartTokenize(s: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '<', '{' -> {
                    val end = findMatchingClose(s, i)
                    if (end < 0) {
                        tokens.add(s[i].toString())
                        i++
                    } else {
                        val inner = s.substring(i + 1, end)
                        if (hasTopLevelComma(inner)) {
                            tokens.add(s[i].toString())
                            i++
                        } else {
                            tokens.add(s.substring(i, end + 1))
                            i = end + 1
                        }
                    }
                }
                '>', '}', ',' -> {
                    tokens.add(s[i].toString())
                    i++
                }
                else -> {
                    val start = i
                    while (i < s.length && s[i] !in "<>{},") i++
                    tokens.add(s.substring(start, i))
                }
            }
        }
        return tokens
    }

    private fun findMatchingClose(
        s: String,
        openIdx: Int,
    ): Int {
        val open = s[openIdx]
        val close = if (open == '<') '>' else '}'
        var depth = 1
        var i = openIdx + 1
        while (i < s.length && depth > 0) {
            when (s[i]) {
                open -> depth++
                close -> depth--
            }
            i++
        }
        return if (depth == 0) i - 1 else -1
    }

    private fun hasTopLevelComma(s: String): Boolean {
        var depth = 0
        for (c in s) {
            when (c) {
                '<', '{' -> depth++
                '>', '}' -> depth--
                ',' -> if (depth == 0) return true
            }
        }
        return false
    }

    private fun markup(
        def: ExpressionDef?,
        fromLibrary: String? = null,
    ): MarkupContent? {
        if (def == null) return null
        if (def is FunctionDef) return markupFunction(def, fromLibrary)
        if (def.expression == null) return null
        val name = def.name ?: return null
        val type = def.resultType ?: return null
        return cqlBlock("""${accessPrefix(def.accessLevel)}define "$name": ${formatCqlType(type.toString())}""", fromLibrary)
    }

    private fun markupFunction(
        def: FunctionDef,
        fromLibrary: String? = null,
    ): MarkupContent? {
        val name = def.name ?: return null
        val returnType = def.resultType ?: return null
        val paramStrings =
            def.operand.map { op ->
                val typeName = op.resultType?.toString() ?: op.operandType?.localPart ?: "?"
                "${op.name ?: "_"} ${formatCqlType(typeName)}"
            }
        val paramsStr =
            if (paramStrings.size >= 3) {
                paramStrings.joinToString(",\n  ", "(\n  ", "\n)")
            } else {
                paramStrings.joinToString(", ", "(", ")")
            }
        val formattedReturnType = formatCqlType(returnType.toString())
        val fluentStr = if (def.fluent == true) "fluent " else ""
        return cqlBlock(
            """${accessPrefix(def.accessLevel)}define ${fluentStr}function $name$paramsStr: $formattedReturnType""",
            fromLibrary,
        )
    }

    private fun markupValueSet(
        def: ValueSetDef?,
        fromLibrary: String? = null,
    ): MarkupContent? {
        if (def == null) return null
        val name = def.name ?: return null
        val id = def.id ?: return null
        return cqlBlock("""${accessPrefix(def.accessLevel)}valueset "$name": '$id'""", fromLibrary)
    }

    private fun markupCodeSystem(
        def: CodeSystemDef?,
        fromLibrary: String? = null,
    ): MarkupContent? {
        if (def == null) return null
        val name = def.name ?: return null
        val id = def.id ?: return null
        return cqlBlock("""${accessPrefix(def.accessLevel)}codesystem "$name": '$id'""", fromLibrary)
    }

    private fun markupForLibraryAlias(
        alias: String,
        library: Library,
    ): MarkupContent? {
        val inc = library.includes?.def?.firstOrNull { it.localIdentifier == alias } ?: return null
        val path = inc.path ?: return null
        val version = inc.version
        val text =
            if (version != null) {
                "include $path version '$version' called $alias"
            } else {
                "include $path called $alias"
            }
        return cqlBlock(text)
    }

    private fun markupCode(
        def: CodeDef?,
        fromLibrary: String? = null,
    ): MarkupContent? {
        if (def == null) return null
        val name = def.name ?: return null
        val id = def.id ?: return null
        val systemName = def.codeSystem?.name
        val prefix = accessPrefix(def.accessLevel)
        val codeStr =
            if (systemName != null) {
                """${prefix}code "$name": '$id' from "$systemName""""
            } else {
                """${prefix}code "$name": '$id'"""
            }
        return cqlBlock(codeStr, fromLibrary)
    }

    private fun markupConcept(
        def: ConceptDef?,
        fromLibrary: String? = null,
    ): MarkupContent? {
        if (def == null) return null
        val name = def.name ?: return null
        val codes = def.code?.joinToString(", ") { it.name ?: "?" } ?: ""
        return cqlBlock("""${accessPrefix(def.accessLevel)}concept "$name": { $codes }""", fromLibrary)
    }

    private fun cqlBlock(
        text: String,
        fromLibrary: String? = null,
    ): MarkupContent {
        val sections = mutableListOf("```cql\n$text\n```")
        if (fromLibrary != null) sections.add("*$fromLibrary*")
        return MarkupContent("markdown", sections.joinToString("\n\n"))
    }

    private fun markup(
        type: DataType?,
        fromLibrary: String? = null,
    ): MarkupContent? {
        val rt = type ?: return null
        return cqlBlock(formatCqlType(rt.toString()), fromLibrary)
    }
}
