package org.opencds.cqf.cql.ls.server.visitor

import org.cqframework.cql.elm.visiting.BaseElmLibraryVisitor
import org.eclipse.lsp4j.Position
import org.hl7.elm.r1.AliasRef
import org.hl7.elm.r1.AliasedQuerySource
import org.hl7.elm.r1.CodeSystemRef
import org.hl7.elm.r1.Element
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.ExpressionRef
import org.hl7.elm.r1.FunctionDef
import org.hl7.elm.r1.FunctionRef
import org.hl7.elm.r1.OperatorExpression
import org.hl7.elm.r1.Property
import org.hl7.elm.r1.Retrieve
import org.hl7.elm.r1.ValueSetRef
import org.hl7.elm.r1.With
import org.hl7.elm.r1.Without
import org.opencds.cqf.cql.ls.server.utility.Elements

/**
 * Walks an ELM library AST and returns the most specific [Element] whose
 * source-location [Element.locator] covers the given [Position] (LSP 0-indexed).
 *
 * Used by [HoverProvider] to determine what is under the cursor.
 */
class ExpressionTrackBackVisitor : BaseElmLibraryVisitor<Element?, Position>() {
    override fun defaultResult(
        elm: Element,
        context: Position,
    ): Element? = null

    /** Prefer child result (more specific) over ancestor result. */
    override fun aggregateResult(
        aggregate: Element?,
        nextResult: Element?,
    ): Element? = nextResult ?: aggregate

    override fun visitExpressionDef(
        elm: ExpressionDef,
        context: Position,
    ): Element? {
        // FunctionDef extends ExpressionDef; dispatch explicitly so visitFunctionDef is honored.
        if (elm is FunctionDef) return visitFunctionDef(elm, context)
        // Let super visit children (calls visitFields internally).
        val childResult = super.visitExpressionDef(elm, context)
        // Prefer child (more specific) but fall back to this def if cursor is inside its range.
        return aggregateResult(if (Elements.containsPosition(elm, context)) elm else null, childResult)
    }

    override fun visitFunctionDef(
        elm: FunctionDef,
        context: Position,
    ): Element? {
        val childResult = super.visitFunctionDef(elm, context)
        return aggregateResult(if (Elements.containsPosition(elm, context)) elm else null, childResult)
    }

    override fun visitOperatorExpression(
        elm: OperatorExpression,
        context: Position,
    ): Element? {
        // Recurse into operands first so a named element (ExpressionRef, FunctionRef, etc.)
        // inside an operator wins as the most-specific match.
        val childResult = super.visitOperatorExpression(elm, context)
        // Return the operator node itself when no child matched and the cursor is within it.
        // markupForElement has no case for OperatorExpression, so hover returns null — correct
        // for operator keywords like `or`, `and`, `+`, etc.  This also prevents the enclosing
        // ExpressionDef from being returned as a false-positive fallback for those positions.
        return aggregateResult(if (Elements.containsPosition(elm, context)) elm else null, childResult)
    }

    override fun visitRetrieve(
        elm: Retrieve,
        context: Position,
    ): Element? {
        // Recurse into children (e.g. the codes ValueSetRef) so that hovering over
        // "Hypotension" in ["Condition": "Hypotension"] returns the ValueSetRef, not the Retrieve.
        val childResult = super.visitRetrieve(elm, context)
        return aggregateResult(if (Elements.containsPosition(elm, context)) elm else null, childResult)
    }

    // Must override BOTH visitExpressionRef and visitFunctionRef:
    // visitElement dispatches FunctionRef directly to visitFunctionRef,
    // so overriding only visitExpressionRef would miss FunctionRef nodes.

    override fun visitExpressionRef(
        elm: ExpressionRef,
        context: Position,
    ): Element? = if (Elements.containsPosition(elm, context)) elm else null

    override fun visitFunctionRef(
        elm: FunctionRef,
        context: Position,
    ): Element? {
        // Recurse into operands: the compiler emits implicit FunctionRef wrappers (e.g.
        // FHIRHelpers.ToDateTime) with no locator around typed expressions like
        // Patient.deceased. Without recursion, those null-locator wrappers silently
        // swallow their children and hover returns nothing for the nested expression.
        val childResult = super.visitFunctionRef(elm, context)
        return aggregateResult(if (Elements.containsPosition(elm, context)) elm else null, childResult)
    }

    override fun visitAliasRef(
        elm: AliasRef,
        context: Position,
    ): Element? = if (Elements.containsPosition(elm, context)) elm else null

    override fun visitAliasedQuerySource(
        elm: AliasedQuerySource,
        context: Position,
    ): Element? {
        val childResult = super.visitAliasedQuerySource(elm, context)
        return aggregateResult(if (Elements.containsPosition(elm, context)) elm else null, childResult)
    }

    override fun visitProperty(
        elm: Property,
        context: Position,
    ): Element? {
        // Recurse into source so that source-based properties (e.g. ExpressionRef.path)
        // return the source node when the cursor is over it. Prefer the child result
        // (more specific) but fall back to this Property if it covers the cursor.
        //
        // NOTE: scope-based properties (Property.scope = "Alias", source = null) are handled
        // in HoverProvider.hover(), not here. Returning null from visitProperty would cause
        // the enclosing ExpressionDef to fill in as a fallback via aggregateResult.
        val childResult = super.visitProperty(elm, context)
        return aggregateResult(if (Elements.containsPosition(elm, context)) elm else null, childResult)
    }

    override fun visitValueSetRef(
        elm: ValueSetRef,
        context: Position,
    ): Element? = if (Elements.containsPosition(elm, context)) elm else null

    override fun visitCodeSystemRef(
        elm: CodeSystemRef,
        context: Position,
    ): Element? = if (Elements.containsPosition(elm, context)) elm else null

    // visitAliasedQuerySource is NOT called for With/Without — the base class dispatches
    // relationship clauses directly via visitRelationshipClause → visitWith/visitWithout.
    // Override both so that hovering over a with/without alias returns the clause node itself,
    // falling back to the clause if no child (expression, suchThat) covers the cursor position.

    override fun visitWith(
        elm: With,
        context: Position,
    ): Element? {
        val childResult = super.visitWith(elm, context)
        return aggregateResult(if (Elements.containsPosition(elm, context)) elm else null, childResult)
    }

    override fun visitWithout(
        elm: Without,
        context: Position,
    ): Element? {
        val childResult = super.visitWithout(elm, context)
        return aggregateResult(if (Elements.containsPosition(elm, context)) elm else null, childResult)
    }
}
