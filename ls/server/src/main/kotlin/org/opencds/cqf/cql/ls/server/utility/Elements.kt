package org.opencds.cqf.cql.ls.server.utility

import org.eclipse.lsp4j.Position
import org.hl7.elm.r1.*

object Elements {
    fun containsPosition(
        elm: Element,
        p: Position,
    ): Boolean {
        val locator = elm.locator ?: return false
        return TrackBacks.containsPosition(locator, p)
    }

    /**
     * Unwraps compiler-generated coercion wrappers from an ELM element,
     * descending through intermediate nodes (As, SingletonFrom, implicit Query)
     * when inner nodes lack locators, to reach the user-intended expression.
     */
    fun unwrapCoercions(
        elm: Element,
        position: Position,
    ): Element {
        var current = elm
        while (current is FunctionRef && current.operand.size == 1 && current.libraryName != null) {
            val op = current.operand.first()
            when {
                op.locator != null && containsPosition(op, position) -> current = op
                op is Property -> current = op
                op is FunctionRef && op.operand.size == 1 && op.libraryName != null -> current = op
                op.isIntermediateNode() -> {
                    val found = deepFindUserNode(op)
                    if (found != null) return found
                    break
                }
                else -> break
            }
        }
        return current
    }

    /**
     * Recursively searches for a user-intended node inside a compiler-generated
     * wrapper subtree. Descends through intermediate nodes to reach the
     * innermost user expression.
     */
    private fun deepFindUserNode(elm: Element): Element? {
        when (elm) {
            is AliasRef -> if (elm.name != "\$this") return elm
            is ExpressionRef -> return elm
            is FunctionRef -> if (elm.libraryName == null) return elm
            is Retrieve -> return elm
            is CodeRef -> return elm
            is ValueSetRef -> return elm
            is CodeSystemRef -> return elm
            is ConceptRef -> return elm
            is OperandRef -> return elm
            is ParameterRef -> return elm
            is Literal -> return elm
        }
        return deepFindUserNodeRecurse(elm)
    }

    private fun deepFindUserNodeRecurse(elm: Element): Element? {
        val result: Element? =
            when {
                elm is SingletonFrom -> elm.operand?.let { deepFindUserNode(it) }
                elm is As -> elm.operand?.let { deepFindUserNode(it) }
                elm is FunctionRef -> elm.operand.firstNotNullOfOrNull { deepFindUserNode(it) }
                elm is Query -> {
                    elm.source.firstNotNullOfOrNull { it.expression?.let { e -> deepFindUserNode(e) } }
                        ?: elm.where?.let { deepFindUserNode(it) }
                        ?: elm.`return`?.expression?.let { deepFindUserNode(it) }
                        ?: elm.relationship.firstNotNullOfOrNull {
                            val expr = it.expression?.let { e -> deepFindUserNode(e) }
                            val st = (it as? With)?.suchThat?.let { s -> deepFindUserNode(s) }
                                ?: (it as? Without)?.suchThat?.let { s -> deepFindUserNode(s) }
                            expr ?: st
                        }
                }
                elm is AliasedQuerySource -> elm.expression?.let { deepFindUserNode(it) }
                elm is Property -> elm.source?.let { deepFindUserNode(it) }
                elm is ReturnClause -> elm.expression?.let { deepFindUserNode(it) }
                else -> null
            }
        return result
    }

    private fun Element.isIntermediateNode(): Boolean =
        this is As || this is SingletonFrom || (this is Query && this.locator == null)
}
