package org.opencds.cqf.cql.ls.server.visitor

import org.cqframework.cql.gen.cqlParser
import org.cqframework.cql.gen.cqlParser.AliasedQuerySourceContext
import org.cqframework.cql.gen.cqlParser.LetClauseItemContext
import org.cqframework.cql.gen.cqlParser.QueryContext

object CqlStepPositionCollector {
    fun collect(parseTree: cqlParser.LibraryContext): Set<Int> {
        val lines = mutableSetOf<Int>()
        collectFromContext(parseTree, lines)
        return lines
    }

    fun collectBreakpointableLines(parseTree: cqlParser.LibraryContext): Set<Int> {
        val lines = mutableSetOf<Int>()
        collectBreakpointableLinesFrom(parseTree, lines)
        return lines
    }

    private fun collectFromContext(
        ctx: ParserRuleContext,
        lines: MutableSet<Int>,
    ) {
        for (i in 0 until ctx.childCount) {
            val child = ctx.getChild(i)
            when (child) {
                is cqlParser.StatementContext -> collectStatement(child, lines)
                is cqlParser.ExpressionDefinitionContext -> collectExpressionDef(child, lines)
                is cqlParser.FunctionDefinitionContext -> collectFunctionDef(child, lines)
                is cqlParser.QueryContext -> collectQuery(child, lines)
                is cqlParser.ExpressionContext -> collectExpression(child, lines)
                is cqlParser.ExpressionTermContext -> collectExpressionTerm(child, lines)
                is cqlParser.RetrieveContext -> child.start?.line?.let { lines.add(it) }
                is cqlParser.QueryInclusionClauseContext -> {
                    child.withClause()?.expression()?.start?.line?.let { lines.add(it) }
                    child.withoutClause()?.expression()?.start?.line?.let { lines.add(it) }
                }
                is ParserRuleContext -> collectFromContext(child, lines)
            }
        }
    }

    private fun collectStatement(
        stmt: cqlParser.StatementContext,
        lines: MutableSet<Int>,
    ) {
        val exprDef = stmt.expressionDefinition()
        if (exprDef != null) {
            val body = exprDef.expression()
            if (body != null) {
                body.start?.line?.let { lines.add(it) }
                collectExpression(body, lines)
            }
        }
        val funcDef = stmt.functionDefinition()
        if (funcDef != null) {
            collectFunctionDef(funcDef, lines)
        }
        collectFromContext(stmt, lines)
    }

    private fun collectExpressionDef(
        exprDef: cqlParser.ExpressionDefinitionContext,
        lines: MutableSet<Int>,
    ) {
        val body = exprDef.expression()
        if (body != null) {
            body.start?.line?.let { lines.add(it) }
            collectExpression(body, lines)
        }
    }

    private fun collectFunctionDef(
        funcDef: cqlParser.FunctionDefinitionContext,
        lines: MutableSet<Int>,
    ) {
        for (i in 0 until funcDef.childCount) {
            val child = funcDef.getChild(i)
            if (child is cqlParser.ExpressionContext) {
                child.start?.line?.let { lines.add(it) }
                collectExpression(child, lines)
            }
        }
    }

    private fun collectExpression(
        exprCtx: cqlParser.ExpressionContext,
        lines: MutableSet<Int>,
    ) {
        collectFromContext(exprCtx, lines)
    }

    private fun collectExpressionTerm(
        termCtx: cqlParser.ExpressionTermContext,
        lines: MutableSet<Int>,
    ) {
        collectFromContext(termCtx, lines)
    }

    private fun collectQuery(
        queryCtx: QueryContext,
        lines: MutableSet<Int>,
    ) {
        val sourceClause = queryCtx.sourceClause()
        if (sourceClause != null) {
            for (i in 0 until sourceClause.childCount) {
                val child = sourceClause.getChild(i)
                if (child is AliasedQuerySourceContext) {
                    child.start?.line?.let { lines.add(it) }
                }
            }
        }

        queryCtx.whereClause()?.expression()?.start?.line?.let { lines.add(it) }
        queryCtx.returnClause()?.expression()?.start?.line?.let { lines.add(it) }

        val letClause = queryCtx.letClause()
        if (letClause != null) {
            for (i in 0 until letClause.childCount) {
                val child = letClause.getChild(i)
                if (child is LetClauseItemContext) {
                    child.expression()?.start?.line?.let { lines.add(it) }
                }
            }
        }

        queryCtx.aggregateClause()?.expression()?.start?.line?.let { lines.add(it) }

        queryCtx.sortClause()?.let { sort ->
            for (item in sort.sortByItem()) {
                item.expressionTerm()?.start?.line?.let { lines.add(it) }
            }
        }

        collectFromContext(queryCtx, lines)
    }

    private fun collectBreakpointableLinesFrom(
        ctx: ParserRuleContext,
        lines: MutableSet<Int>,
    ) {
        for (i in 0 until ctx.childCount) {
            val child = ctx.getChild(i)
            when (child) {
                is cqlParser.ExpressionContext -> {
                    child.start?.line?.let { lines.add(it) }
                    collectBreakpointableLinesFrom(child, lines)
                }
                is cqlParser.ExpressionTermContext -> {
                    child.start?.line?.let { lines.add(it) }
                    collectBreakpointableLinesFrom(child, lines)
                }
                is ParserRuleContext -> collectBreakpointableLinesFrom(child, lines)
            }
        }
    }
}

private typealias ParserRuleContext = org.antlr.v4.kotlinruntime.ParserRuleContext
