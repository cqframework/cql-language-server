package org.opencds.cqf.cql.ls.server.provider

import org.cqframework.cql.cql2elm.CqlCompiler
import org.cqframework.cql.cql2elm.tracking.Trackable.resultType
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.hl7.cql.model.ClassType
import org.hl7.cql.model.DataType
import org.hl7.cql.model.ListType
import org.hl7.elm.r1.AccessModifier
import org.hl7.elm.r1.CodeDef
import org.hl7.elm.r1.CodeSystemDef
import org.hl7.elm.r1.ConceptDef
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.FunctionDef
import org.hl7.elm.r1.Library
import org.hl7.elm.r1.ParameterDef
import org.hl7.elm.r1.ValueSetDef
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.hl7.elm.r1.VersionedIdentifier
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

private val TOP_LEVEL_KEYWORDS = listOf(
    "define", "function", "parameter", "using", "include", "library",
    "valueset", "codesystem", "code", "concept", "context",
    "public", "private", "fluent",
)

private val SYSTEM_FUNCTIONS = listOf(
    "Count", "Sum", "Min", "Max", "Avg", "First", "Last",
    "Exists", "Not", "IsNull", "IsTrue", "IsFalse",
    "ToInteger", "ToDecimal", "ToString", "ToDate", "ToDateTime",
    "Flatten", "Distinct",
    "Union", "Intersect", "Except",
    "Contains", "In", "Includes", "IncludedIn",
)

private val DOT_PATTERN = Regex("""(\w+)\.(\w*)$""")

private sealed interface CompletionContext {
    data object TopLevel : CompletionContext
    data object ExpressionBody : CompletionContext
    data class LibraryQualified(val alias: String, val prefix: String) : CompletionContext
    data class DotAccess(val receiverText: String, val prefix: String) : CompletionContext
    data object Unknown : CompletionContext
}

class CompletionProvider(
    private val compilationManager: CqlCompilationManager,
    private val contentService: ContentService,
    private val hoverProvider: HoverProvider,
) {
    fun completion(params: CompletionParams): List<CompletionItem> {
        val uri = Uris.parseOrNull(params.textDocument.uri) ?: return emptyList()
        val compiler = compilationManager.compile(uri) ?: return emptyList()
        val library = compiler.library ?: return emptyList()
        val parseTree = compilationManager.getParseTree(uri)
        val context = detectContext(uri, params.position, parseTree)

        return when (context) {
            is CompletionContext.TopLevel -> completionsForTopLevel()
            is CompletionContext.ExpressionBody -> completionsForExpressionBody(compiler, library)
            is CompletionContext.LibraryQualified -> completionsForLibraryQualified(context.alias, context.prefix, library, uri, params.position)
            is CompletionContext.DotAccess -> completionsForDotAccess(context.receiverText, context.prefix, compiler, library, parseTree, params.position, uri)
            is CompletionContext.Unknown -> emptyList()
        }
    }

    // ── Context detection ──────────────────────────────────────────────────────

    private fun detectContext(
        uri: URI,
        position: Position,
        parseTree: org.cqframework.cql.gen.cqlParser.LibraryContext?,
    ): CompletionContext {
        val lineText = readLineUpToCursor(uri, position) ?: return CompletionContext.Unknown

        val fromParseTree = parseTree?.let { mapCursorCategory(CursorClassifier.classify(it, position), lineText) }
        if (fromParseTree != null && fromParseTree !is CompletionContext.Unknown) {
            // When ANTLR returns ExpressionBody but the line has a dot suffix (e.g. "WP." or "E."),
            // try the text-scan fallback to detect library-qualified or property-access context
            if (fromParseTree is CompletionContext.ExpressionBody && DOT_PATTERN.containsMatchIn(lineText)) {
                val textCtx = detectContextFromText(lineText, position)
                if (textCtx !is CompletionContext.ExpressionBody && textCtx !is CompletionContext.Unknown) {
                    return textCtx
                }
            }
            return fromParseTree
        }

        return detectContextFromText(lineText, position)
    }

    private fun mapCursorCategory(
        category: CursorCategory,
        lineText: String,
    ): CompletionContext {
        return when (category) {
            is CursorCategory.LibraryAlias -> {
                CompletionContext.LibraryQualified(category.name, extractTextAfterLastDot(lineText))
            }
            is CursorCategory.PropertyName -> {
                val receiverText = category.aliasName ?: return CompletionContext.Unknown
                CompletionContext.DotAccess(receiverText, extractTextAfterLastDot(lineText))
            }
            is CursorCategory.AliasReference -> {
                CompletionContext.DotAccess(category.name, extractTextAfterLastDot(lineText))
            }
            is CursorCategory.ExpressionRef,
            is CursorCategory.FunctionCall,
            is CursorCategory.ParameterRef,
            is CursorCategory.ExpressionDefName,
            is CursorCategory.FunctionDefName,
            is CursorCategory.ParameterDefName,
            is CursorCategory.OperandRef,
            is CursorCategory.Retrieve,
            is CursorCategory.Literal,
            -> CompletionContext.ExpressionBody

            is CursorCategory.KeywordSuppress -> CompletionContext.ExpressionBody
            is CursorCategory.Unknown -> CompletionContext.Unknown
            else -> CompletionContext.ExpressionBody
        }
    }

    private fun detectContextFromText(
        lineText: String,
        position: Position,
    ): CompletionContext {
        val dotMatch = DOT_PATTERN.find(lineText)
        if (dotMatch != null) {
            return CompletionContext.DotAccess(
                receiverText = dotMatch.groupValues[1],
                prefix = dotMatch.groupValues[2],
            )
        }

        if (position.character == 0) {
            return CompletionContext.TopLevel
        }

        return CompletionContext.ExpressionBody
    }

    // ── Line reading ───────────────────────────────────────────────────────────

    private fun readLineUpToCursor(uri: URI, position: Position): String? {
        val stream = contentService.read(uri) ?: return null
        val text = stream.bufferedReader().readText()
        val lines = text.split("\n", limit = position.line + 1)
        if (position.line >= lines.size) return null
        val line = lines[position.line]
        if (position.character > line.length) return null
        return line.substring(0, position.character)
    }

    private fun extractTextAfterLastDot(lineText: String): String {
        val dotIndex = lineText.lastIndexOf('.')
        if (dotIndex < 0) return ""
        return lineText.substring(dotIndex + 1)
    }

    private fun makeDotColumn(lineText: String): Int = lineText.lastIndexOf('.')

    private fun makeReplaceRange(lineText: String, position: Position): Range? {
        val dotIndex = makeDotColumn(lineText)
        if (dotIndex < 0) return null
        return Range(
            Position(position.line, dotIndex + 1),
            Position(position.line, position.character),
        )
    }

    // ── Source cache ──────────────────────────────────────────────────────────

    private val sourceCache = ConcurrentHashMap<URI, String?>()

    // ── Completion builders ────────────────────────────────────────────────────

    private fun completionsForTopLevel(): List<CompletionItem> =
        TOP_LEVEL_KEYWORDS.map { keyword ->
            CompletionItem(keyword).apply {
                kind = CompletionItemKind.Keyword
                insertText = keyword
                sortText = "0_$keyword"
            }
        }

    private fun completionsForExpressionBody(
        compiler: CqlCompiler,
        library: Library,
    ): List<CompletionItem> {
        val items = mutableListOf<CompletionItem>()

        library.statements?.def?.forEach { def ->
            when (def) {
                is FunctionDef -> items.add(makeFunctionItem(def))
                is ExpressionDef -> items.add(makeExpressionItem(def))
            }
        }

        library.parameters?.def?.forEach { param ->
            items.add(makeParameterItem(param))
        }

        library.valueSets?.def?.forEach { vs ->
            items.add(makeTerminologyItem(vs, CompletionItemKind.Enum, vs.id))
        }
        library.codeSystems?.def?.forEach { cs ->
            items.add(makeTerminologyItem(cs, CompletionItemKind.Module, cs.id))
        }
        library.codes?.def?.forEach { code ->
            val detail = code.codeSystem?.name
            items.add(makeTerminologyItem(code, CompletionItemKind.EnumMember, detail))
        }
        library.concepts?.def?.forEach { concept ->
            items.add(makeTerminologyItem(concept, CompletionItemKind.Struct, null))
        }

        SYSTEM_FUNCTIONS.forEach { fn ->
            items.add(
                CompletionItem(fn).apply {
                    kind = CompletionItemKind.Function
                    insertText = fn
                    sortText = "2_$fn"
                },
            )
        }

        return items
    }

    private fun completionsForLibraryQualified(
        alias: String,
        prefix: String,
        library: Library,
        uri: URI,
        position: Position,
    ): List<CompletionItem> {
        val included = hoverProvider.resolveIncludedLibrary(alias, library, uri) ?: return emptyList()
        val incLibrary = included.library ?: return emptyList()
        val items = mutableListOf<CompletionItem>()
        val lineText = readLineUpToCursor(uri, position) ?: ""
        val replaceRange = makeReplaceRange(lineText, position)
        val incUri = findIncludedLibraryUri(alias, library, uri)
        incLibrary.statements?.def?.forEach { def ->
            if (!isPrivateDef(def, incUri)) {
                when (def) {
                    is FunctionDef -> items.add(makeFunctionItem(def, replaceRange = replaceRange))
                    is ExpressionDef -> items.add(makeExpressionItem(def, replaceRange = replaceRange))
                }
            }
        }

        incLibrary.parameters?.def?.forEach { param ->
            items.add(makeParameterItem(param, replaceRange = replaceRange))
        }

        incLibrary.valueSets?.def?.forEach { vs ->
            items.add(makeTerminologyItem(vs, CompletionItemKind.Enum, vs.id, replaceRange))
        }
        incLibrary.codeSystems?.def?.forEach { cs ->
            items.add(makeTerminologyItem(cs, CompletionItemKind.Module, cs.id, replaceRange))
        }
        incLibrary.codes?.def?.forEach { code ->
            items.add(makeTerminologyItem(code, CompletionItemKind.EnumMember, code.codeSystem?.name, replaceRange))
        }
        incLibrary.concepts?.def?.forEach { concept ->
            items.add(makeTerminologyItem(concept, CompletionItemKind.Struct, null, replaceRange))
        }

        return items
    }

    private fun completionsForDotAccess(
        receiverText: String,
        prefix: String,
        compiler: CqlCompiler,
        library: Library,
        parseTree: org.cqframework.cql.gen.cqlParser.LibraryContext?,
        position: Position,
        uri: URI,
    ): List<CompletionItem> {
        // Dot after a library alias → redirect to library-qualified completion
        if (library.includes?.def?.any { it.localIdentifier == receiverText } == true) {
            return completionsForLibraryQualified(receiverText, prefix, library, uri, position)
        }

        if (parseTree == null) return emptyList()

        val lineText = readLineUpToCursor(uri, position) ?: ""
        val replaceRange = makeReplaceRange(lineText, position) ?: return emptyList()
        val dotColumn = makeDotColumn(lineText)
        val lookupPosition = if (dotColumn >= 0) Position(position.line, dotColumn) else position

        val typeName = hoverProvider.resolveAliasTypeFromAntlrWithContext(parseTree, lookupPosition, receiverText, library)
            ?: return emptyList()

        val modelManager = compiler.libraryManager?.modelManager ?: return emptyList()
        val (modelQualifier, simpleTypeName) = splitTypeName(typeName)
        val model =
            if (modelQualifier != null) {
                modelManager.resolveModel(modelQualifier)
            } else {
                modelManager.globalCache.values.firstOrNull { it.resolveTypeName(simpleTypeName) != null }
            } ?: return emptyList()

        val resolvedType = model.resolveTypeName(simpleTypeName) ?: return emptyList()
        val classType =
            if (resolvedType is ListType) {
                resolvedType.elementType as? ClassType
            } else {
                resolvedType as? ClassType
            } ?: return emptyList()

        return classType.allElements.map { element ->
            CompletionItem(element.name).apply {
                kind = CompletionItemKind.Property
                insertText = element.name
                detail = element.type?.toString()
                sortText = "1_${element.name}"
                textEdit = Either.forLeft(TextEdit(replaceRange, element.name))
            }
        }
    }

    private fun findIncludedLibraryUri(alias: String, library: Library, uri: URI): URI? {
        val root = Uris.getHead(uri)
        val includeDef = library.includes?.def?.firstOrNull { it.localIdentifier == alias } ?: return null
        val identifier = VersionedIdentifier().apply {
            id = includeDef.path ?: return null
            version = includeDef.version
        }
        return contentService.locate(root, identifier).firstOrNull()
    }

    private fun isPrivateDef(def: Any, incUri: URI?): Boolean {
        val accessLevel = (def as? ExpressionDef)?.accessLevel
        if (accessLevel == AccessModifier.PRIVATE) return true

        // For definitions using non-standard "private define" syntax the CQL parser does not
        // recognise the access modifier and the compiler emits PUBLIC. Fall back to source text.
        if (incUri == null) return false
        val source = sourceCache.getOrPut(incUri) {
            try {
                contentService.read(incUri)?.bufferedReader()?.readText()
            } catch (e: Exception) {
                null
            }
        } ?: return false
        val name = (def as? ExpressionDef)?.name ?: return false
        return source.contains("private define \"$name\"") ||
               source.contains("define private function \"$name\"") ||
               source.contains("define private \"$name\"")
    }

    // ── Item factories ─────────────────────────────────────────────────────────

    private fun makeExpressionItem(
        def: ExpressionDef,
        replaceRange: Range? = null,
    ): CompletionItem {
        val name = def.name ?: "?"
        return CompletionItem(name).apply {
            kind = CompletionItemKind.Variable
            insertText = "\"$name\""
            detail = def.resultType?.toString()
            sortText = "1_$name"
            if (replaceRange != null) {
                textEdit = Either.forLeft(TextEdit(replaceRange, "\"$name\""))
            }
        }
    }

    private fun makeFunctionItem(
        def: FunctionDef,
        replaceRange: Range? = null,
    ): CompletionItem {
        val name = def.name ?: "?"
        val paramStrings = (def.operand ?: emptyList()).map { op ->
            val typeName = op.resultType?.toString() ?: op.operandType?.localPart ?: "?"
            "${op.name ?: "_"} $typeName"
        }
        return CompletionItem(name).apply {
            kind = CompletionItemKind.Function
            insertText = "\"$name\""
            detail = paramStrings.joinToString(", ", "(", ")")
            sortText = "1_$name"
            if (replaceRange != null) {
                textEdit = Either.forLeft(TextEdit(replaceRange, "\"$name\""))
            }
        }
    }

    private fun makeParameterItem(
        def: ParameterDef,
        replaceRange: Range? = null,
    ): CompletionItem {
        val name = def.name ?: "?"
        return CompletionItem(name).apply {
            kind = CompletionItemKind.Constant
            insertText = "\"$name\""
            detail = def.resultType?.toString() ?: def.parameterType?.localPart
            sortText = "1_$name"
            if (replaceRange != null) {
                textEdit = Either.forLeft(TextEdit(replaceRange, "\"$name\""))
            }
        }
    }

    private fun makeTerminologyItem(
        def: Any,
        kind: CompletionItemKind,
        detail: String?,
        replaceRange: Range? = null,
    ): CompletionItem {
        val name = when (def) {
            is ValueSetDef -> def.name
            is CodeSystemDef -> def.name
            is CodeDef -> def.name
            is ConceptDef -> def.name
            else -> return CompletionItem("?")
        } ?: "?"
        return CompletionItem(name).apply {
            this.kind = kind
            insertText = "\"$name\""
            this.detail = detail
            sortText = "1_$name"
            if (replaceRange != null) {
                textEdit = Either.forLeft(TextEdit(replaceRange, "\"$name\""))
            }
        }
    }

    private fun splitTypeName(typeName: String): Pair<String?, String> {
        val dotIndex = typeName.lastIndexOf('.')
        return if (dotIndex >= 0) {
            typeName.substring(0, dotIndex) to typeName.substring(dotIndex + 1)
        } else {
            null to typeName
        }
    }
}
