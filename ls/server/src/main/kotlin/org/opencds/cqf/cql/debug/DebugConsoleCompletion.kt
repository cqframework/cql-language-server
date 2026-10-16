package org.opencds.cqf.cql.debug

import org.eclipse.lsp4j.debug.CompletionItem
import org.eclipse.lsp4j.debug.CompletionItemType

/**
 * The partial identifier or dotted path being completed in a Debug Console line.
 *
 * @property rootText text before the last top-level `.` (e.g. `E.period` for `E.period.st|`),
 *   or `null` when completing a top-level name.
 * @property prefix the partial segment being typed. May start with an opening `"` or `` ` ``.
 * @property replaceStart 0-based offset in the line where [prefix] starts.
 */
data class CompletionTarget(
    val rootText: String?,
    val prefix: String,
    val replaceStart: Int,
)

private enum class ScanState { NORMAL, SINGLE_QUOTE, DOUBLE_QUOTE, BACKTICK }

private val SIMPLE_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

private fun isIdentifierChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

/**
 * Finds the trailing identifier or dotted path that ends at [cursor] in [line], so that only the
 * word being typed is completed, even inside a longer ad-hoc expression
 * (`E.period ends before IndexPCP.per|` → root `IndexPCP`, prefix `per`).
 *
 * Returns `null` when there is nothing completable: the cursor is inside a `'string'` literal,
 * the line is an `@line:col` position lookup, the segment being typed is an index
 * (`name[0]|`), or the root or segment starts with a digit (a number, not a name).
 */
fun findCompletionTarget(
    line: String,
    cursor: Int,
): CompletionTarget? {
    if (line.trimStart().startsWith("@")) return null
    val text = line.substring(0, cursor.coerceIn(0, line.length))

    var state = ScanState.NORMAL
    var tokenStart = 0
    var lastDot = -1
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when (state) {
            ScanState.SINGLE_QUOTE, ScanState.DOUBLE_QUOTE, ScanState.BACKTICK -> {
                val close =
                    when (state) {
                        ScanState.SINGLE_QUOTE -> '\''
                        ScanState.DOUBLE_QUOTE -> '"'
                        else -> '`'
                    }
                if (c == '\\') {
                    i++
                } else if (c == close) {
                    state = ScanState.NORMAL
                }
            }
            ScanState.NORMAL ->
                when {
                    c == '\'' -> {
                        state = ScanState.SINGLE_QUOTE
                        tokenStart = i + 1
                        lastDot = -1
                    }
                    c == '"' || c == '`' -> {
                        // A quoted identifier is part of the path only at its start or right after a dot.
                        if (i != tokenStart && text[i - 1] != '.') {
                            tokenStart = i
                            lastDot = -1
                        }
                        state = if (c == '"') ScanState.DOUBLE_QUOTE else ScanState.BACKTICK
                    }
                    c == '.' -> lastDot = i
                    c == '[' -> {
                        // An indexer only continues a path (`name[0]`); a bare `[` starts a retrieve.
                        val prev = text.getOrNull(i - 1)
                        if (i == tokenStart || prev == null || !(isIdentifierChar(prev) || prev == ']' || prev == '"' || prev == '`')) {
                            tokenStart = i + 1
                            lastDot = -1
                        }
                    }
                    c == ']' || isIdentifierChar(c) -> Unit
                    else -> {
                        tokenStart = i + 1
                        lastDot = -1
                    }
                }
        }
        i++
    }
    if (state == ScanState.SINGLE_QUOTE) return null

    val prefixStart = if (lastDot >= tokenStart) lastDot + 1 else tokenStart
    val prefix = text.substring(prefixStart)
    if (prefix.contains('[') || prefix.contains(']')) return null
    if (prefix.firstOrNull()?.isDigit() == true) return null
    val rootText = if (lastDot >= tokenStart) text.substring(tokenStart, lastDot) else null
    if (rootText != null && (rootText.isEmpty() || rootText.first().isDigit())) return null
    return CompletionTarget(rootText, prefix, prefixStart)
}

/**
 * Builds Debug Console completions from values the debugger already holds at the current pause.
 * There is no type inference: top-level names come from [RuntimeValueRegistry] (the same names
 * `evaluate()` resolves) and members come from the actual paused value via
 * [VariableResolver.childrenNamesOf].
 */
class DebugConsoleCompleter(
    private val variableResolver: VariableResolver,
) {
    fun complete(
        target: CompletionTarget,
        registry: RuntimeValueRegistry,
    ): List<CompletionItem> =
        if (target.rootText == null) {
            completeTopLevel(target, registry)
        } else {
            completeMembers(target, target.rootText, registry)
        }

    private fun completeTopLevel(
        target: CompletionTarget,
        registry: RuntimeValueRegistry,
    ): List<CompletionItem> {
        // Same scoping priority as RuntimeValueRegistry.find: stack > define > context > parameter.
        val ranked =
            listOf(
                registry.getStackVariables().sortedBy { it.name } to CompletionItemType.VARIABLE,
                registry.getDefinesByLibrary().values.flatten().sortedBy { it.name } to CompletionItemType.VALUE,
                registry.getContextResources().sortedBy { it.name } to CompletionItemType.CLASS,
                registry.getParametersByLibrary().values.flatten().sortedBy { it.name } to CompletionItemType.PROPERTY,
            )
        val seen = HashSet<String>()
        val items = mutableListOf<CompletionItem>()
        ranked.forEachIndexed { rank, (values, type) ->
            for (rv in values) {
                if (!seen.add(rv.name)) continue
                val text = encodeName(rv.name, target.prefix)
                items +=
                    CompletionItem().also {
                        it.label = text
                        it.text = text
                        it.type = type
                        it.sortText = "$rank$text"
                        it.detail = detailFor(rv)
                        it.start = target.replaceStart
                        it.length = target.prefix.length
                    }
            }
        }
        return items
    }

    private fun completeMembers(
        target: CompletionTarget,
        rootText: String,
        registry: RuntimeValueRegistry,
    ): List<CompletionItem> {
        val parsed = variableResolver.parseIdentifier(rootText) ?: return emptyList()
        val rv = registry.find(parsed.rootName) ?: return emptyList()
        val path =
            buildList {
                parsed.rootIndex?.let { add("[$it]") }
                addAll(parsed.propertySegments)
            }
        val parent = if (path.isEmpty()) rv.value else variableResolver.navigatePropertyPath(rv.value, path)
        if (parent == null) return emptyList()
        return variableResolver.childrenNamesOf(parent).distinct().sorted().map { name ->
            val text = encodeName(name, target.prefix)
            CompletionItem().also {
                it.label = text
                it.text = text
                it.type = CompletionItemType.PROPERTY
                it.sortText = text
                it.start = target.replaceStart
                it.length = target.prefix.length
            }
        }
    }

    /** Quotes [name] when it isn't a simple identifier, or when the user already opened a quote. */
    private fun encodeName(
        name: String,
        prefix: String,
    ): String {
        val delimiter =
            when {
                prefix.startsWith("`") -> "`"
                prefix.startsWith("\"") -> "\""
                SIMPLE_IDENTIFIER.matches(name) -> return name
                else -> "\""
            }
        return "$delimiter$name$delimiter"
    }

    private fun detailFor(rv: RuntimeValue): String {
        val category =
            when (rv.category) {
                RuntimeValueCategory.STACK_VARIABLE -> "local"
                RuntimeValueCategory.DEFINE -> "define"
                RuntimeValueCategory.CONTEXT_RESOURCE -> "context"
                RuntimeValueCategory.PARAMETER -> "parameter"
            }
        return listOfNotNull(
            rv.libraryName?.let { "$category ($it)" } ?: category,
            rv.type,
        ).joinToString(": ")
    }
}
