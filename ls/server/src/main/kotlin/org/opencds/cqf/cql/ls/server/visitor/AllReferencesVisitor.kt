package org.opencds.cqf.cql.ls.server.visitor

import org.cqframework.cql.elm.visiting.BaseElmLibraryVisitor
import org.eclipse.lsp4j.Location
import org.hl7.elm.r1.CodeRef
import org.hl7.elm.r1.CodeSystemRef
import org.hl7.elm.r1.ConceptRef
import org.hl7.elm.r1.Element
import org.hl7.elm.r1.ExpressionRef
import org.hl7.elm.r1.FunctionRef
import org.hl7.elm.r1.ValueSetRef
import org.opencds.cqf.cql.ls.server.utility.TrackBacks
import java.net.URI

/**
 * Walks an ELM library AST and collects every reference to the symbol named
 * [context] (an exact-match on the local name, ignoring library qualifier).
 *
 * Produces a [List<Location>] of all matching ref nodes, with each [Location]
 * anchored to the [uri] of the library being visited.
 *
 * Used by [ReferencesProvider] to implement "Find All References".
 */
class AllReferencesVisitor(private val uri: URI) : BaseElmLibraryVisitor<List<Location>, String>() {
    override fun defaultResult(
        elm: Element,
        context: String,
    ): List<Location> = emptyList()

    override fun aggregateResult(
        aggregate: List<Location>,
        nextResult: List<Location>,
    ): List<Location> = aggregate + nextResult

    // Must override BOTH visitExpressionRef and visitFunctionRef.
    // visitElement dispatches FunctionRef directly to visitFunctionRef,
    // so overriding only visitExpressionRef would miss FunctionRef nodes.

    override fun visitExpressionRef(
        elm: ExpressionRef,
        context: String,
    ): List<Location> {
        if (elm.name != context) return emptyList()
        val range = elm.locator?.let { TrackBacks.toRange(it) } ?: return emptyList()
        return listOf(Location(uri.toString(), range))
    }

    override fun visitFunctionRef(
        elm: FunctionRef,
        context: String,
    ): List<Location> {
        if (elm.name != context) return emptyList()
        val range = elm.locator?.let { TrackBacks.toRange(it) } ?: return emptyList()
        return listOf(Location(uri.toString(), range))
    }

    override fun visitValueSetRef(
        elm: ValueSetRef,
        context: String,
    ): List<Location> {
        if (elm.name != context) return emptyList()
        val range = elm.locator?.let { TrackBacks.toRange(it) } ?: return emptyList()
        return listOf(Location(uri.toString(), range))
    }

    override fun visitCodeRef(
        elm: CodeRef,
        context: String,
    ): List<Location> {
        if (elm.name != context) return emptyList()
        val range = elm.locator?.let { TrackBacks.toRange(it) } ?: return emptyList()
        return listOf(Location(uri.toString(), range))
    }

    override fun visitConceptRef(
        elm: ConceptRef,
        context: String,
    ): List<Location> {
        if (elm.name != context) return emptyList()
        val range = elm.locator?.let { TrackBacks.toRange(it) } ?: return emptyList()
        return listOf(Location(uri.toString(), range))
    }

    override fun visitCodeSystemRef(
        elm: CodeSystemRef,
        context: String,
    ): List<Location> {
        if (elm.name != context) return emptyList()
        val range = elm.locator?.let { TrackBacks.toRange(it) } ?: return emptyList()
        return listOf(Location(uri.toString(), range))
    }
}
