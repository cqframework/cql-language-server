package org.opencds.cqf.cql.ls.server.provider

import org.antlr.v4.kotlinruntime.ParserRuleContext
import org.cqframework.cql.cql2elm.CqlCompiler
import org.cqframework.cql.cql2elm.tracking.Trackable.resultType
import org.cqframework.cql.gen.cqlParser
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.hl7.cql.model.DataType
import org.hl7.elm.r1.AccessModifier
import org.hl7.elm.r1.AliasRef
import org.hl7.elm.r1.AliasedQuerySource
import org.hl7.elm.r1.CodeDef
import org.hl7.elm.r1.CodeRef
import org.hl7.elm.r1.CodeSystemDef
import org.hl7.elm.r1.CodeSystemRef
import org.hl7.elm.r1.ConceptDef
import org.hl7.elm.r1.ConceptRef
import org.hl7.elm.r1.Element
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.ExpressionRef
import org.hl7.elm.r1.FunctionDef
import org.hl7.elm.r1.FunctionRef
import org.hl7.elm.r1.LetClause
import org.hl7.elm.r1.Library
import org.hl7.elm.r1.Literal
import org.hl7.elm.r1.OperandDef
import org.hl7.elm.r1.OperandRef
import org.hl7.elm.r1.ParameterDef
import org.hl7.elm.r1.ParameterRef
import org.hl7.elm.r1.Property
import org.hl7.elm.r1.Query
import org.hl7.elm.r1.Retrieve
import org.hl7.elm.r1.ValueSetDef
import org.hl7.elm.r1.ValueSetRef
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.elm.r1.With
import org.hl7.elm.r1.Without
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.utility.Elements
import org.opencds.cqf.cql.ls.server.utility.TrackBacks
import org.opencds.cqf.cql.ls.server.visitor.CqlParseTreeVisitor
import org.opencds.cqf.cql.ls.server.visitor.ExpressionTrackBackVisitor
import java.net.URI
import org.hl7.elm.r1.If as IfElm

class HoverProvider(
    private val compilationManager: CqlCompilationManager,
    private val contentService: ContentService,
) {
    fun hover(params: HoverParams): Hover? {
        val uri = Uris.parseOrNull(params.textDocument.uri) ?: return null
        val compiler = compilationManager.compile(uri) ?: return null
        val library = compiler.library ?: return null
        val raw = ExpressionTrackBackVisitor().visitLibrary(library, params.position) ?: return null
        val resolved = Elements.unwrapCoercions(raw, params.position)

        // ANTLR-based with/without classification (preferred path)
        if (resolved is With || resolved is Without) {
            val parseTree = compilationManager.getParseTree(uri)
            if (parseTree != null) {
                val result = resolveWithWithoutHover(resolved, parseTree, params, compiler, uri, library)
                if (result != null) return result
                // null from resolveWithWithoutHover means "fall through to ELM path below"
                // (not "suppress"). Skip shouldSuppressWithWithout either way.
            } else {
                if (shouldSuppressWithWithout(resolved, params)) return null
            }
        }

        aliasForScopeProperty(resolved, params, compiler, uri, library)?.let { return it }
        aliasForCompilerAccessor(resolved, params, compiler, uri, library)?.let { return it }
        if (shouldSuppressFunctionRefPrefix(resolved, params)) return null
        crossLibraryAliasHover(resolved, params, library)?.let { return it }

    val range = (resolved.locator ?: raw.locator)?.let { TrackBacks.toRange(it) }
    val markup = markupForElement(resolved, compiler, uri, library, params.position) ?: return null
    return Hover(markup, range)
}

    /**
     * For scope-based property access (Property.scope = alias name, source = null)
     * the alias portion before the `.` has no child ELM node. When the cursor is
     * over the alias name, resolve the alias and show its type instead of the
     * path result type.
     *
     * When the Property has no locator (compiler coercion context), we cannot
     * distinguish cursor on the scope prefix vs. the property name. Fall back
     * to alias resolution — showing alias info is always more helpful than
     * showing the coercion function signature.
     */
    private fun aliasForScopeProperty(
        resolved: Element,
        params: HoverParams,
        compiler: CqlCompiler,
        uri: URI,
        library: Library,
    ): Hover? {
        if (resolved !is Property || resolved.scope == null) return null
        val propRange = resolved.locator?.let { TrackBacks.toRange(it) }
        if (propRange != null &&
            (params.position.line != propRange.start.line ||
                params.position.character >= propRange.start.character + resolved.scope!!.length)
        ) return null
        val aliasMarkup = markupForAlias(resolved.scope!!, compiler, uri, library, params.position)
        if (aliasMarkup != null) {
            val range = resolved.locator?.let { TrackBacks.toRange(it) }
            return Hover(aliasMarkup, range)
        }
        return null
    }

    /**
     * Compiler-generated accessor Properties (e.g. `.value` on a FHIR code type)
     * wrap a scope-based Property (the real user-intended access) but inherit its
     * locator. The returned ELM has scope=null, so [aliasForScopeProperty] doesn't
     * fire. When the cursor is over the alias portion of the inner scope-based
     * Property, resolve the alias and show its type.
     */
    private fun aliasForCompilerAccessor(
        resolved: Element,
        params: HoverParams,
        compiler: CqlCompiler,
        uri: URI,
        library: Library,
    ): Hover? {
        if (resolved !is Property || resolved.scope != null || resolved.source !is Property) return null
        val scopeProp = resolved.source as Property
        if (scopeProp.scope == null) return null
        val propRange = resolved.locator?.let { TrackBacks.toRange(it) } ?: return null
        if (params.position.line != propRange.start.line ||
            params.position.character >= propRange.start.character + scopeProp.scope!!.length
        ) return null
        val aliasMarkup = markupForAlias(scopeProp.scope!!, compiler, uri, library, params.position)
        if (aliasMarkup != null) {
            val range = resolved.locator?.let { TrackBacks.toRange(it) }
            return Hover(aliasMarkup, range)
        }
        return null
    }

    /**
     * Suppress hover for query relationship keywords ("with", "such that").
     * Returns true when the cursor is on a syntactic keyword that should not
     * produce a hover popup.
     */
    private fun shouldSuppressWithWithout(resolved: Element, params: HoverParams): Boolean {
        if (resolved !is With && resolved !is Without) return false
        val exprRange = resolved.expression?.locator?.let { TrackBacks.toRange(it) }
        if (exprRange != null &&
            (
                params.position.line < exprRange.start.line ||
                    (params.position.line == exprRange.start.line && params.position.character < exprRange.start.character)
            )
        ) {
            return true
        }
        val st =
            when (resolved) {
                is With -> resolved.suchThat
                is Without -> resolved.suchThat
                else -> null
            }
        if (st != null && exprRange != null) {
            val stRange = st.locator?.let { TrackBacks.toRange(it) }
            if (stRange != null) {
                val afterExpr =
                    params.position.line > exprRange.end.line ||
                        (params.position.line == exprRange.end.line && params.position.character > exprRange.end.character)
                val beforeSt =
                    params.position.line < stRange.start.line ||
                        (params.position.line == stRange.start.line && params.position.character < stRange.start.character)
                if (afterExpr && beforeSt) {
                    if (params.position.line == exprRange.end.line &&
                        params.position.line == stRange.start.line
                    ) {
                        val aliasLen = resolved.alias?.length ?: 0
                        val aliasEnd = exprRange.end.character + 1 + aliasLen
                        if (params.position.character > aliasEnd) return true
                    } else {
                        if (params.position.line > exprRange.end.line) return true
                    }
                }
            }
        }
        return false
    }

    /**
     * Suppress hover when the cursor lands on a syntactic prefix of a FunctionRef
     * rather than the expression itself. Two known patterns:
     *
     * 1. FHIR property coercion: FunctionRef locator covers "E.period", inner
     *    Property locator covers "period". Cursor on "E" → suppress.
     *
     * 2. Fluent function in a where/with clause: where Alias.someFunc() — the
     *    compiler assigns a locator starting at "where". Cursor on "where" → suppress.
     *
     * Regular function calls like foo(a, b) have other expression types as first
     * operand and are unaffected.
     */
    private fun shouldSuppressFunctionRefPrefix(resolved: Element, params: HoverParams): Boolean {
        if (resolved !is FunctionRef || resolved.operand.isEmpty()) return false
        val firstOp = resolved.operand.first()
        val opStart =
            when {
                firstOp is Property && firstOp.scope != null ->
                    firstOp.locator?.let { TrackBacks.toRange(it) }?.start
                firstOp is AliasRef ->
                    firstOp.locator?.let { TrackBacks.toRange(it) }?.start
                else -> null
            }
        if (opStart != null &&
            (
                params.position.line < opStart.line ||
                    (params.position.line == opStart.line &&
                        params.position.character < opStart.character)
            )
        ) {
            return true
        }
        return false
    }

    /**
     * When the cursor is on the library alias prefix of a cross-library reference
     * (e.g. cursor on "SDE" in `SDE."SDE Sex"`), show the include declaration
     * rather than the referenced expression's details.
     */
    private fun crossLibraryAliasHover(
        resolved: Element,
        params: HoverParams,
        library: Library,
    ): Hover? {
        val refLibraryName =
            when (resolved) {
                is ExpressionRef -> resolved.libraryName
                is FunctionRef -> resolved.libraryName
                is ValueSetRef -> resolved.libraryName
                is CodeSystemRef -> resolved.libraryName
                is CodeRef -> resolved.libraryName
                is ConceptRef -> resolved.libraryName
                is ParameterRef -> resolved.libraryName
                else -> null
            } ?: return null
        val refRange = resolved.locator?.let { TrackBacks.toRange(it) } ?: return null
        if (params.position.line != refRange.start.line ||
            params.position.character >= refRange.start.character + refLibraryName.length
        ) return null
        val markup = markupForLibraryAlias(refLibraryName, library) ?: return null
        val aliasRange =
            Range(
                Position(refRange.start.line, refRange.start.character),
                Position(refRange.start.line, refRange.start.character + refLibraryName.length),
            )
        return Hover(markup, aliasRange)
    }

    /**
     * ANTLR-based resolution for With/Without clauses. Classifies the cursor position
     * by comparing it against the syntactic sub-regions of the clause parse tree:
     *
     *   with/without | aliasedQuerySource | such that | expression
     *                 ├─ querySource ─alias─┤           │
     *
     * Returns null when the cursor is on a region that should fall through to the
     * existing ELM TrackBack path (source expression or suchThat expression).
     * Returns a Hover for the alias region. Returns null for keyword regions
     * (the caller interprets null as "suppress" for keywords, "fall through" else).
     */
    private fun resolveWithWithoutHover(
        resolved: Element,
        parseTree: cqlParser.LibraryContext,
        params: HoverParams,
        compiler: CqlCompiler,
        uri: URI,
        library: Library,
    ): Hover? {
        val cursorCtx = CqlParseTreeVisitor.findDeepestContext(parseTree, params.position) ?: return null
        // Walk up the ANTLR tree to find the enclosing WithClauseContext or WithoutClauseContext.
        var current: ParserRuleContext? = cursorCtx
        while (current != null && current !is cqlParser.WithClauseContext && current !is cqlParser.WithoutClauseContext) {
            current = current.getParent() as? ParserRuleContext
        }
        val aqs: cqlParser.AliasedQuerySourceContext
        val exprCtx: cqlParser.ExpressionContext
        when (current) {
            is cqlParser.WithClauseContext -> {
                aqs = current.aliasedQuerySource()
                exprCtx = current.expression()
            }
            is cqlParser.WithoutClauseContext -> {
                aqs = current.aliasedQuerySource()
                exprCtx = current.expression()
            }
            else -> return null
        }
        return resolveClauseHover(aqs, exprCtx, params, compiler, uri, library)
    }

    /**
     * Shared logic for both WithClauseContext and WithoutClauseContext,
     * since both have the same internal structure:
     *   (with|without) aliasedQuerySource 'such that' expression
     */
    private fun resolveClauseHover(
        aqs: cqlParser.AliasedQuerySourceContext,
        exprCtx: cqlParser.ExpressionContext,
        params: HoverParams,
        compiler: CqlCompiler,
        uri: URI,
        library: Library,
    ): Hover? {
        val pos = params.position

        // Cursor before the aliasedQuerySource → "with"/"without" keyword
        val srcStart = aqs.start ?: return null
        val srcStartLine = srcStart.line - 1
        val srcStartChar = srcStart.charPositionInLine
        if (pos.line < srcStartLine || (pos.line == srcStartLine && pos.character < srcStartChar)) {
            return null
        }

        // Cursor within the alias identifier itself
        val aliasCtx = aqs.alias()
        val aliasStop = aliasCtx.stop ?: return null
        val aliasEndLine = aliasStop.line - 1
        val aliasEndChar = aliasStop.charPositionInLine + (aliasStop.text?.length ?: 0)
        if (pos.line < aliasEndLine || (pos.line == aliasEndLine && pos.character < aliasEndChar)) {
            val aliasName = aliasCtx.identifier().text
            val aliasMarkup = markupForAlias(aliasName, compiler, uri, library, pos) ?: return null
            val aliasStart = aliasCtx.start ?: return null
            val aliasRange = Range(
                Position(aliasStart.line - 1, aliasStart.charPositionInLine),
                Position(aliasEndLine, aliasEndChar),
            )
            return Hover(aliasMarkup, aliasRange)
        }

        // Cursor between the end of aliasedQuerySource and the start of expression →
        // "such that" keyword region
        val aqsStop = aqs.stop ?: return null
        val aqsEndLine = aqsStop.line - 1
        val aqsEndChar = aqsStop.charPositionInLine + (aqsStop.text?.length ?: 0)
        val exprStart = exprCtx.start ?: return null
        val exprStartLine = exprStart.line - 1
        val exprStartChar = exprStart.charPositionInLine
        val afterSource = pos.line > aqsEndLine || (pos.line == aqsEndLine && pos.character > aqsEndChar)
        val beforeExpr = pos.line < exprStartLine || (pos.line == exprStartLine && pos.character < exprStartChar)
        if (afterSource && beforeExpr) return null

        // Within source expression (before alias) or within suchThat expression →
        // fall through to existing ELM TrackBack path
        return null
    }

    private fun markupForElement(
        elm: Element,
        compiler: CqlCompiler,
        uri: URI,
        library: Library,
        position: Position? = null,
    ): MarkupContent? {
        val fromLibrary = fromLibraryForElement(elm, library)
        // NOTE: With/Without extend AliasedQuerySource in the ELM model.
        // They must be checked BEFORE AliasedQuerySource to avoid false matches.
        // For With/Without, we return null because:
        // - Keyword hover is suppressed (handled by resolveWithWithoutHover)
        // - Alias hover is handled by resolveWithWithoutHover
        // - Expression hover is handled by the child ELM nodes
        if (elm is With || elm is Without) return null
        return when (elm) {
            is ExpressionDef -> markup(elm, fromLibrary)
            is FunctionRef -> {
                val name = elm.name ?: return null
                val libAlias = elm.libraryName
                val resolved =
                    if (libAlias == null) {
                        compiler.compiledLibrary?.resolveFunctionRef(name)?.firstOrNull()
                    } else {
                        resolveIncludedLibrary(libAlias, library, uri)
                            ?.compiledLibrary?.resolveFunctionRef(name)?.firstOrNull()
                    }
                markup(resolved, fromLibrary) ?: markup(elm.resultType, fromLibrary)
            }
            is ExpressionRef -> {
                val name = elm.name ?: return null
                val libAlias = elm.libraryName
                val resolved =
                    if (libAlias == null) {
                        try {
                            compiler.compiledLibrary?.resolveExpressionRef(name)
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        resolveIncludedLibrary(libAlias, library, uri)
                            ?.compiledLibrary?.resolveExpressionRef(name)
                    }
                markup(resolved, fromLibrary)
            }
            is ValueSetRef -> {
                val name = elm.name ?: return null
                val libAlias = elm.libraryName
                val resolved =
                    if (libAlias == null) {
                        try {
                            compiler.compiledLibrary?.resolveValueSetRef(name)
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        resolveIncludedLibrary(libAlias, library, uri)
                            ?.compiledLibrary?.resolveValueSetRef(name)
                    }
                markupValueSet(resolved, fromLibrary)
            }
            is CodeSystemRef -> {
                val name = elm.name ?: return null
                val libAlias = elm.libraryName
                val resolved =
                    if (libAlias == null) {
                        try {
                            compiler.compiledLibrary?.resolveCodeSystemRef(name)
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        resolveIncludedLibrary(libAlias, library, uri)
                            ?.compiledLibrary?.resolveCodeSystemRef(name)
                    }
                markupCodeSystem(resolved, fromLibrary)
            }
            is Retrieve -> markup(elm.resultType, fromLibrary)
            is AliasRef -> {
                val name = elm.name ?: return null
                val rt = elm.resultType
                if (rt != null) {
                    cqlBlock("""$name: ${formatCqlType(rt.toString())}""", fromLibrary)
                } else {
                    markupForAlias(name, compiler, uri, library, position)
                }
            }
            is AliasedQuerySource -> {
                val alias = elm.alias ?: return null
                val rt = elm.resultType ?: return null
                cqlBlock("""(alias) $alias: ${formatCqlType(rt.toString())}""", fromLibrary)
            }
            is Property -> {
                val rt = elm.resultType ?: return null
                // Walk past compiler-generated accessors (e.g. ".value" on a FHIR code type)
                // to find the user-intended property access for labeling.
                val userProp =
                    generateSequence(elm) { p ->
                        if (p.scope == null && p.source is Property) p.source as Property else null
                    }.lastOrNull() ?: elm
                val pathLabel =
                    if (userProp.scope != null) {
                        "${userProp.scope}.${userProp.path}"
                    } else if (userProp.source is ExpressionRef) {
                        "\"${(userProp.source as ExpressionRef).name}\".${userProp.path}"
                    } else {
                        ".${elm.path}"
                    }
                cqlBlock("""(element) $pathLabel: ${formatCqlType(rt.toString())}""", fromLibrary)
            }
            is Literal -> markup(elm.resultType, fromLibrary)
            is OperandRef -> {
                val rt = elm.resultType ?: return null
                val name = elm.name ?: return null
                cqlBlock("""parameter "$name": ${formatCqlType(rt.toString())}""", fromLibrary)
            }
            is OperandDef -> {
                val rt = elm.resultType ?: return null
                val name = elm.name ?: return null
                cqlBlock("""parameter "$name": ${formatCqlType(rt.toString())}""", fromLibrary)
            }
            is ParameterRef -> {
                val name = elm.name ?: return null
                val libAlias = elm.libraryName
                val resolved =
                    if (libAlias == null) {
                        library.parameters?.def?.firstOrNull { it.name == name }
                    } else {
                        resolveIncludedLibrary(libAlias, library, uri)
                            ?.library?.parameters?.def?.firstOrNull { it.name == name }
                    }
                if (resolved != null) {
                    val rt = resolved.resultType ?: return null
                    cqlBlock("""parameter "${resolved.name}": ${formatCqlType(rt.toString())}""", fromLibrary)
                } else {
                    markup(elm.resultType, fromLibrary)
                }
            }
            is ParameterDef -> {
                val rt = elm.resultType ?: return null
                val name = elm.name ?: return null
                cqlBlock("""parameter "$name": ${formatCqlType(rt.toString())}""", fromLibrary)
            }
            is CodeRef -> {
                val name = elm.name ?: return null
                val libAlias = elm.libraryName
                val resolved =
                    if (libAlias == null) {
                        try {
                            compiler.compiledLibrary?.resolveCodeRef(name)
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        resolveIncludedLibrary(libAlias, library, uri)
                            ?.compiledLibrary?.resolveCodeRef(name)
                    }
                markupCode(resolved, fromLibrary)
            }
            is ConceptRef -> {
                val name = elm.name ?: return null
                val libAlias = elm.libraryName
                val resolved =
                    if (libAlias == null) {
                        try {
                            compiler.compiledLibrary?.resolveConceptRef(name)
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        resolveIncludedLibrary(libAlias, library, uri)
                            ?.compiledLibrary?.resolveConceptRef(name)
                    }
                markupConcept(resolved, fromLibrary)
            }
            else -> null
        }
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

    private fun fromLibraryForElement(
        elm: Element,
        library: Library,
    ): String? {
        val libAlias =
            when (elm) {
                is ExpressionRef -> elm.libraryName
                is FunctionRef -> elm.libraryName
                is ValueSetRef -> elm.libraryName
                is CodeSystemRef -> elm.libraryName
                is CodeRef -> elm.libraryName
                is ConceptRef -> elm.libraryName
                is ParameterRef -> elm.libraryName
                else -> null
            }
        return libAlias?.let { libraryLabel(it, library) } ?: localLibraryLabel(library)
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

    private fun findAliasSource(
        elm: Element?,
        alias: String,
    ): Element? {
        if (elm == null) return null
        when (elm) {
            is AliasedQuerySource -> if (elm.alias == alias) return elm
            is LetClause -> if (elm.identifier == alias) return elm
        }
        return when (elm) {
            is Query ->
                elm.source.firstNotNullOfOrNull { findAliasSource(it, alias) }
                    ?: elm.relationship.firstNotNullOfOrNull { findAliasSource(it, alias) }
                    ?: elm.let?.firstNotNullOfOrNull { findAliasSource(it, alias) }
            is ExpressionDef -> findAliasSource(elm.expression, alias)
            is IfElm -> findAliasSource(elm.then, alias) ?: findAliasSource(elm.`else`, alias)
            else -> null
        }
    }

    private fun formatCqlType(type: String): String {
        val highlightedType =
            type
                .replace(Regex("\\blist<"), "List<")
                .replace(Regex("\\binterval<"), "Interval<")
                .replace(Regex("\\btuple\\{"), "Tuple{")
                .replace(Regex("\\bchoice<"), "Choice<")
        if (highlightedType.length <= 50) return highlightedType
        val sb = StringBuilder()
        var indent = 0
        val step = "  "
        val tokens = highlightedType.split(Regex("(?<=[<>{,])|(?=[<>{},])")).filter { it.isNotBlank() }
        for (i in tokens.indices) {
            val token = tokens[i].trim()
            when (token) {
                "<", "{" -> {
                    sb.append(token).append("\n").append(step.repeat(++indent))
                }
                ">", "}" -> {
                    indent--
                    val next = if (i + 1 < tokens.size) tokens[i + 1].trim() else ""
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
            """${accessPrefix(def.accessLevel)}define ${fluentStr}function "$name"$paramsStr: $formattedReturnType""",
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
