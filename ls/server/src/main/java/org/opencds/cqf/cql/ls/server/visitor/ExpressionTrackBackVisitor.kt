package org.opencds.cqf.cql.ls.server.visitor

import org.cqframework.cql.cql2elm.tracking.TrackBack
import org.cqframework.cql.cql2elm.tracking.Trackable.trackbacks
import org.cqframework.cql.elm.visiting.BaseElmLibraryVisitor
import org.hl7.elm.r1.Element
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.Retrieve

open class ExpressionTrackBackVisitor : BaseElmLibraryVisitor<Element?, TrackBack?>() {

    // Return the child result if it's not null (i.e., it's more specific than the current result).
    // Otherwise, return the current result.
    override fun aggregateResult(aggregate: Element?, nextResult: Element?): Element? {
        return if (nextResult != null) nextResult else aggregate
    }

    override fun visitExpressionDef(elm: ExpressionDef, context: TrackBack?): Element? {
        val childResult = super.visitExpressionDef(elm, context)
        return aggregateResult(if (context != null && elementCoversTrackBack(elm, context)) elm else null, childResult)
    }

    override fun visitRetrieve(retrieve: Retrieve, context: TrackBack?): Element? {
        return if (context != null && elementCoversTrackBack(retrieve, context)) retrieve else null
    }

    protected fun elementCoversTrackBack(elm: Element, context: TrackBack): Boolean {
        for (tb in elm.trackbacks) {
            if (startsOnOrBefore(tb, context) && endsOnOrAfter(tb, context)) {
                return true
            }
        }
        return false
    }

    protected fun startsOnOrBefore(left: TrackBack, right: TrackBack): Boolean {
        if (left.startLine > right.startLine) return false
        if (left.startLine < right.startLine) return true
        // Same line
        return left.startChar <= right.startChar
    }

    protected fun endsOnOrAfter(left: TrackBack, right: TrackBack): Boolean {
        if (left.endLine < right.endLine) return false
        if (left.endLine > right.endLine) return true
        // Same line
        return left.endChar >= right.endChar
    }

    override fun defaultResult(element: Element, trackBack: TrackBack?): Element? = null
}
