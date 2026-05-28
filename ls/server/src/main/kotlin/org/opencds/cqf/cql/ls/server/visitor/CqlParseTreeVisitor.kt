package org.opencds.cqf.cql.ls.server.visitor

import org.antlr.v4.kotlinruntime.ParserRuleContext
import org.eclipse.lsp4j.Position

/**
 * Walks an ANTLR CQL parse tree and returns the deepest [ParserRuleContext]
 * whose source span contains the given LSP [Position].
 *
 * ANTLR Token positions:
 * - line: 1-based
 * - charPositionInLine: 0-based
 * - exclusive end: charPositionInLine + text.length
 *
 * LSP Position: 0-based line and character.
 */
object CqlParseTreeVisitor {
    fun findDeepestContext(
        root: ParserRuleContext,
        position: Position,
    ): ParserRuleContext? {
        if (root.start != null && root.stop != null) {
            if (!containsPosition(root, position)) return null
        }
        // Iterate the children list directly rather than using childCount +
        // getChild(i), because labeled alternative nodes created via ANTLR
        // copyFrom may have a non-null children list whose size is reflected
        // by childCount inconsistently in some runtime versions.
        val children = root.children
        if (children != null) {
            for (child in children) {
                if (child is ParserRuleContext) {
                    val deeper = findDeepestContext(child, position)
                    if (deeper != null) return deeper
                }
            }
        }
        return root
    }

    private fun containsPosition(
        ctx: ParserRuleContext,
        p: Position,
    ): Boolean {
        val start = ctx.start ?: return false
        val stop = ctx.stop ?: return false
        val startLine = start.line - 1
        val startChar = start.charPositionInLine
        val stopLine = stop.line - 1
        val stopChar = stop.charPositionInLine + (stop.text?.length ?: 0)
        return when {
            p.line < startLine -> false
            p.line > stopLine -> false
            p.line == startLine && p.character < startChar -> false
            p.line == stopLine && p.character >= stopChar -> false
            else -> true
        }
    }
}
