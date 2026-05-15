package org.opencds.cqf.cql.ls.server.provider

import org.cqframework.cql.cql2elm.CqlCompiler
import org.cqframework.cql.cql2elm.tracking.Trackable.resultType
import org.cqframework.cql.cql2elm.tracking.Trackable.trackbacks
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.MarkupContent
import org.hl7.cql.model.DataType
import org.hl7.elm.r1.AliasRef
import org.hl7.elm.r1.AliasedQuerySource
import org.hl7.elm.r1.CodeSystemDef
import org.hl7.elm.r1.CodeSystemRef
import org.hl7.elm.r1.Element
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.ExpressionRef
import org.hl7.elm.r1.FunctionDef
import org.hl7.elm.r1.FunctionRef
import org.hl7.elm.r1.Library
import org.hl7.elm.r1.Property
import org.hl7.elm.r1.Retrieve
import org.hl7.elm.r1.ValueSetDef
import org.hl7.elm.r1.ValueSetRef
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.utility.TrackBacks
import org.opencds.cqf.cql.ls.server.visitor.ExpressionTrackBackVisitor
import java.net.URI

class HoverProvider(
    private val compilationManager: CqlCompilationManager,
    private val contentService: ContentService,
) {
    fun hover(params: HoverParams): Hover? {
        val uri = Uris.parseOrNull(params.textDocument.uri) ?: return null
        val compiler = compilationManager.compile(uri) ?: return null
        val library = compiler.library ?: return null
        val elm = ExpressionTrackBackVisitor().visitLibrary(library, params.position) ?: return null
        // When the cursor lands on a position that is inside a FunctionRef's locator but before
        // the first operand's locator, it is on a syntactic prefix rather than the expression
        // itself. Two known patterns:
        //
        //  1. FHIR property coercion: FHIRHelpers.ToInterval(Property(scope="E", path="period"))
        //     — FunctionRef locator covers "E.period", inner Property locator covers "period".
        //     Cursor on "E" → suppress to avoid showing the coercion function signature.
        //
        //  2. Fluent function in a where/with clause: where Alias.someFunc()
        //     — The compiler assigns a locator to the where-clause expression that starts at
        //     the "where" keyword. Cursor on "where" → suppress to avoid showing the fluent
        //     function signature.
        //
        // Applies when firstOp is a scope-based Property (pattern 1) or an AliasRef (pattern 2).
        // Regular function calls like foo(a, b) have other expression types as first operand and
        // are unaffected.
        if (elm is FunctionRef && elm.operand.isNotEmpty()) {
            val firstOp = elm.operand.first()
            val opStart =
                when {
                    firstOp is Property && firstOp.scope != null ->
                        firstOp.locator?.let { TrackBacks.toRange(it) }?.start
                    firstOp is AliasRef ->
                        firstOp.locator?.let { TrackBacks.toRange(it) }?.start
                    else -> null
                }
            if (opStart != null &&
                (params.position.line < opStart.line ||
                 (params.position.line == opStart.line &&
                  params.position.character < opStart.character))
            ) {
                return null
            }
        }
        // For scope-based property access (Property.scope = alias name, source = null) the alias
        // portion before the `.` has no child ELM node. Suppress hover when the cursor is over
        // the alias name to avoid showing the path result type instead of the alias type.
        if (elm is Property && elm.scope != null) {
            val propRange = elm.locator?.let { TrackBacks.toRange(it) }
            if (propRange != null &&
                params.position.line == propRange.start.line &&
                params.position.character < propRange.start.character + elm.scope!!.length
            ) {
                return null
            }
        }
        // Cross-library ref: cursor on the library alias prefix → show the include declaration.
        // E.g. cursor on "SDE" in `SDE."SDE Sex"` shows the include statement for SDE rather
        // than the details of the referenced expression. Applies to ExpressionRef, FunctionRef,
        // ValueSetRef, and CodeSystemRef — all ref types that carry a libraryName prefix.
        val refLibraryName =
            when (elm) {
                is ExpressionRef -> elm.libraryName
                is FunctionRef -> elm.libraryName
                is ValueSetRef -> elm.libraryName
                is CodeSystemRef -> elm.libraryName
                else -> null
            }
        if (refLibraryName != null) {
            val refRange = elm.locator?.let { TrackBacks.toRange(it) }
            if (refRange != null &&
                params.position.line == refRange.start.line &&
                params.position.character < refRange.start.character + refLibraryName.length
            ) {
                val markup = markupForLibraryAlias(refLibraryName, library) ?: return null
                return Hover(markup, refRange)
            }
        }
        val range = elm.locator?.let { TrackBacks.toRange(it) }
        val markup = markupForElement(elm, compiler, uri, library) ?: return null
        return Hover(markup, range)
    }

    private fun markupForElement(
        elm: Element,
        compiler: CqlCompiler,
        uri: URI,
        library: Library,
    ): MarkupContent? =
        when (elm) {
            is ExpressionDef -> markup(elm.resultType)
            is FunctionRef -> {
                val name = elm.name ?: return null
                val libAlias = elm.libraryName
                val fromLibrary = libAlias?.let { libraryLabel(it, library) }
                // resolveExpressionRef excludes FunctionDefs (they are stored separately in the
                // operator map, not the namespace map). Use resolveFunctionRef, which searches
                // library.statements.def for matching FunctionDef instances.
                val resolved =
                    if (libAlias == null) {
                        compiler.compiledLibrary?.resolveFunctionRef(name)?.firstOrNull()
                    } else {
                        resolveIncludedLibrary(libAlias, library, uri)
                            ?.compiledLibrary?.resolveFunctionRef(name)?.firstOrNull()
                    }
                markup(resolved, fromLibrary) ?: markup(elm.resultType)
            }
            is ExpressionRef -> {
                val name = elm.name ?: return null
                val libAlias = elm.libraryName
                val fromLibrary = libAlias?.let { libraryLabel(it, library) }
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
                val fromLibrary = libAlias?.let { libraryLabel(it, library) }
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
                val fromLibrary = libAlias?.let { libraryLabel(it, library) }
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
            is Retrieve -> markup(elm.resultType)
            is AliasRef -> markup(elm.resultType)
            is AliasedQuerySource -> markup(elm.resultType)
            is Property -> markup(elm.resultType)
            else -> null
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

    fun markup(
        def: ExpressionDef?,
        fromLibrary: String? = null,
    ): MarkupContent? {
        if (def == null) return null
        if (def is FunctionDef) return markupFunction(def, fromLibrary)
        if (def.expression == null) return null
        val name = def.name ?: return null
        val type = def.resultType ?: return null
        return tsMarkup("`$type`", """(define) "$name"""", fromLibrary)
    }

    private fun markupFunction(
        def: FunctionDef,
        fromLibrary: String? = null,
    ): MarkupContent? {
        val name = def.name ?: return null
        val returnType = def.resultType ?: return null
        val params = def.operand.joinToString(", ") { op ->
            val typeName = op.resultType?.toString() ?: op.operandType?.localPart ?: "?"
            "${op.name ?: "_"} $typeName"
        }
        val kind = if (def.fluent == true) "fluent function" else "function"
        return tsMarkup("`$returnType`", """($kind) "$name"($params)""", fromLibrary)
    }

    private fun markupValueSet(
        def: ValueSetDef?,
        fromLibrary: String? = null,
    ): MarkupContent? {
        if (def == null) return null
        val name = def.name ?: return null
        val id = def.id ?: return null
        return tsMarkup("`'$id'`", """(valueset) "$name"""", fromLibrary)
    }

    private fun markupForLibraryAlias(
        alias: String,
        library: Library,
    ): MarkupContent? {
        val inc = library.includes?.def?.firstOrNull { it.localIdentifier == alias } ?: return null
        val path = inc.path ?: return null
        val version = inc.version
        val text =
            if (version != null) "include $path version '$version' called $alias"
            else "include $path called $alias"
        return cqlMarkup(text)
    }

    private fun markupCodeSystem(
        def: CodeSystemDef?,
        fromLibrary: String? = null,
    ): MarkupContent? {
        if (def == null) return null
        val name = def.name ?: return null
        val id = def.id ?: return null
        return tsMarkup("`'$id'`", """(codesystem) "$name"""", fromLibrary)
    }

    private fun tsMarkup(
        type: String,
        definition: String,
        fromLibrary: String? = null,
    ): MarkupContent {
        val sections = buildList {
            add("type: $type")
            add(definition)
            if (fromLibrary != null) add(fromLibrary)
        }
        return MarkupContent("markdown", sections.joinToString("\n\n"))
    }

    private fun markup(type: DataType?): MarkupContent? {
        val rt = type ?: return null
        return cqlMarkup(rt.toString())
    }

    private fun cqlMarkup(text: String): MarkupContent =
        // Specifying the Markdown type as cql allows the client to apply
        // cql syntax highlighting to the resulting pop-up
        MarkupContent("markdown", "```cql\n$text\n```")
}
