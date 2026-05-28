package org.opencds.cqf.cql.ls.server.visitor

import org.cqframework.cql.elm.visiting.BaseElmLibraryVisitor
import org.eclipse.lsp4j.Position
import org.hl7.elm.r1.CodeRef
import org.hl7.elm.r1.CodeSystemRef
import org.hl7.elm.r1.ConceptRef
import org.hl7.elm.r1.Element
import org.hl7.elm.r1.ExpressionRef
import org.hl7.elm.r1.FunctionRef
import org.hl7.elm.r1.IncludeDef
import org.hl7.elm.r1.Literal
import org.hl7.elm.r1.OperandRef
import org.hl7.elm.r1.ParameterRef
import org.hl7.elm.r1.ValueSetRef
import org.opencds.cqf.cql.ls.server.utility.Elements

/**
 * Walks an ELM library AST and returns the navigable [Element] (a ref or include)
 * whose source-location trackback covers the given [Position] (LSP 0-indexed).
 *
 * Used by [DefinitionProvider] and [ReferencesProvider] to find the symbol under the cursor.
 */
class DefinitionTrackBackVisitor : BaseElmLibraryVisitor<Element?, Position>() {
    override fun defaultResult(
        elm: Element,
        context: Position,
    ): Element? = null

    /** Prefer child result (more specific) over ancestor result. */
    override fun aggregateResult(
        aggregate: Element?,
        nextResult: Element?,
    ): Element? = nextResult ?: aggregate

    // Must override BOTH visitExpressionRef and visitFunctionRef.
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
        // Recurse into operands so that arguments (Literal, ExpressionRef, etc.) under the
        // cursor are returned instead of the outer function call. Without recursion, "Go to
        // Definition" on a function argument jumps to the function itself.
        val childResult = super.visitFunctionRef(elm, context)
        return aggregateResult(
            if (Elements.containsPosition(elm, context)) elm else null,
            childResult,
        )
    }

    override fun visitValueSetRef(
        elm: ValueSetRef,
        context: Position,
    ): Element? = if (Elements.containsPosition(elm, context)) elm else null

    override fun visitCodeRef(
        elm: CodeRef,
        context: Position,
    ): Element? = if (Elements.containsPosition(elm, context)) elm else null

    override fun visitConceptRef(
        elm: ConceptRef,
        context: Position,
    ): Element? = if (Elements.containsPosition(elm, context)) elm else null

    override fun visitCodeSystemRef(
        elm: CodeSystemRef,
        context: Position,
    ): Element? = if (Elements.containsPosition(elm, context)) elm else null

    override fun visitIncludeDef(
        elm: IncludeDef,
        context: Position,
    ): Element? = if (Elements.containsPosition(elm, context)) elm else null

    override fun visitOperandRef(
        elm: OperandRef,
        context: Position,
    ): Element? = if (Elements.containsPosition(elm, context)) elm else null

    override fun visitParameterRef(
        elm: ParameterRef,
        context: Position,
    ): Element? = if (Elements.containsPosition(elm, context)) elm else null

    override fun visitLiteral(
        elm: Literal,
        context: Position,
    ): Element? = if (Elements.containsPosition(elm, context)) elm else null
}
