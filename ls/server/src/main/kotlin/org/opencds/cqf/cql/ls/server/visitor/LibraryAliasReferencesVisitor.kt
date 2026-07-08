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
 * Walks an ELM library AST and collects every node that references a library by the
 * alias [context] (matches [ExpressionRef.libraryName], not the local symbol name).
 *
 * Used by [org.opencds.cqf.cql.ls.server.provider.ReferencesProvider] when the cursor
 * is on an include line — to find all `Alias."Foo"` call sites in the current file and
 * in other files that include the same library.
 */
class LibraryAliasReferencesVisitor(private val uri: URI) : BaseElmLibraryVisitor<List<Location>, String>() {
    override fun defaultResult(
        elm: Element,
        context: String,
    ): List<Location> = emptyList()

    override fun aggregateResult(
        aggregate: List<Location>,
        nextResult: List<Location>,
    ): List<Location> = aggregate + nextResult

    override fun visitExpressionRef(
        elm: ExpressionRef,
        context: String,
    ): List<Location> {
        if (elm.libraryName != context) return emptyList()
        val range = elm.locator?.let { TrackBacks.toRange(it) } ?: return emptyList()
        return listOf(Location(uri.toString(), range))
    }

    override fun visitFunctionRef(
        elm: FunctionRef,
        context: String,
    ): List<Location> {
        if (elm.libraryName != context) return emptyList()
        val range = elm.locator?.let { TrackBacks.toRange(it) } ?: return emptyList()
        return listOf(Location(uri.toString(), range))
    }

    override fun visitValueSetRef(
        elm: ValueSetRef,
        context: String,
    ): List<Location> {
        if (elm.libraryName != context) return emptyList()
        val range = elm.locator?.let { TrackBacks.toRange(it) } ?: return emptyList()
        return listOf(Location(uri.toString(), range))
    }

    override fun visitCodeRef(
        elm: CodeRef,
        context: String,
    ): List<Location> {
        if (elm.libraryName != context) return emptyList()
        val range = elm.locator?.let { TrackBacks.toRange(it) } ?: return emptyList()
        return listOf(Location(uri.toString(), range))
    }

    override fun visitConceptRef(
        elm: ConceptRef,
        context: String,
    ): List<Location> {
        if (elm.libraryName != context) return emptyList()
        val range = elm.locator?.let { TrackBacks.toRange(it) } ?: return emptyList()
        return listOf(Location(uri.toString(), range))
    }

    override fun visitCodeSystemRef(
        elm: CodeSystemRef,
        context: String,
    ): List<Location> {
        if (elm.libraryName != context) return emptyList()
        val range = elm.locator?.let { TrackBacks.toRange(it) } ?: return emptyList()
        return listOf(Location(uri.toString(), range))
    }
}
