package org.opencds.cqf.cql.ls.server.provider

import org.cqframework.cql.cql2elm.tracking.TrackBack
import org.cqframework.cql.cql2elm.tracking.Trackable.resultType
import org.cqframework.cql.cql2elm.tracking.Trackable.trackbacks
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.Library.Statements
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager

class HoverProvider(private val cqlCompilationManager: CqlCompilationManager) {

    fun hover(position: HoverParams): Hover? {
        // Returning null here effectively disables hover functionality from the server side.
        // 2026-03-30 RGT: Disable hover functionality for now.
        //                 The LibraryManager caching issues is causing the CQL Compiler to continutally fire.
        //                 This is affect performance, revist hover functionality later.
        return null

        val uri = Uris.parseOrNull(position.textDocument.uri) ?: return null

        // This translates on the fly. We may want to consider maintaining
        // an ELM index to reduce the need to do retranslation.
        val compiler = cqlCompilationManager.compile(uri) ?: return null

        // The ExpressionTrackBackVisitor is supposed to replace this eventually.
        // Basically, for any given position in the text document there's a graph of nodes
        // that represent the parents nodes for that position. For example:
        //
        // define: "EncounterExists":
        // exists([Encounter])
        //
        // ExpressionDef -> Expression -> Exists -> Retrieve
        //
        // For that given position, we want to select the most specific node we support generating
        // hover information for and return that.
        //
        // (maybe the alternative is to select the specific node under the cursor, but that may be less user-friendly)
        //
        // The current code always picks the first ExpressionDef in the graph.
        val exp = getExpressionDefForPosition(
            position.position,
            compiler.compiledLibrary?.library?.statements
        ) ?: return null

        val markup = markup(exp.second) ?: return null

        return Hover(markup, exp.first)
    }

    private fun getExpressionDefForPosition(position: Position, statements: Statements?): Pair<Range, ExpressionDef>? {
        if (statements == null || statements.def.isEmpty()) {
            return null
        }
        for (def in statements.def) {
            if (def.trackbacks.isEmpty()) {
                continue
            }

            for (tb in def.trackbacks) {
                if (positionInTrackBack(position, tb)) {
                    val range = Range(
                        Position(tb.startLine - 1, tb.startChar - 1),
                        Position(tb.endLine - 1, tb.endChar)
                    )
                    return Pair(range, def)
                }
            }
        }

        return null
    }

    private fun positionInTrackBack(p: Position, tb: TrackBack): Boolean {
        val startLine = tb.startLine - 1
        val endLine = tb.endLine - 1

        // Just kidding. We need intervals.
        return p.line in startLine..endLine
    }

    fun markup(def: ExpressionDef?): MarkupContent? {
        if (def == null || def.expression == null) {
            return null
        }

        val resultType = def.resultType ?: return null

        // Specifying the Markdown type as cql allows the client to apply
        // cql syntax highlighting the resulting pop-up
        val result = listOf("```cql", resultType.toString(), "```").joinToString("\n")

        return MarkupContent("markdown", result)
    }
}
