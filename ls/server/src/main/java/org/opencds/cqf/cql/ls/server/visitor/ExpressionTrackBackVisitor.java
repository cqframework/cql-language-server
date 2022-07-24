package org.opencds.cqf.cql.ls.server.visitor;

import org.cqframework.cql.elm.tracking.TrackBack;
import org.cqframework.cql.elm.visiting.ElmBaseLibraryVisitor;
import org.hl7.elm.r1.Element;
import org.hl7.elm.r1.ExpressionDef;
import org.hl7.elm.r1.FunctionDef;
import org.hl7.elm.r1.Retrieve;

public class ExpressionTrackBackVisitor extends ElmBaseLibraryVisitor<Element, TrackBack> {

    // Return the child result if it's not null (IOW, it's more specific than this result).
    // Otherwise, return the current result
    @Override
    protected Element aggregateResult(Element aggregate, Element nextResult) {
        if (nextResult != null) {
            return nextResult;
        }

        return aggregate;
    }

    @Override
    public Element visitExpressionDef(ExpressionDef elm, TrackBack context) {
        if (elm instanceof FunctionDef) {
            return visitFunctionDef((FunctionDef) elm, context);
        }
        Element childResult = visitChildren(elm, context);
        return aggregateResult(elementCoversTrackBack(elm, context) ? elm : null, childResult);
    }

    @Override
    public Element visitRetrieve(Retrieve retrieve, TrackBack context) {
        if (elementCoversTrackBack(retrieve, context)) {
            return retrieve;
        }

        return null;
    }

    protected boolean elementCoversTrackBack(Element elm, TrackBack context) {
        for (TrackBack tb : elm.getTrackbacks()) {
            if (startsOnOrBefore(tb, context) && endsOnOrAfter(tb, context)) {
                return true;
            }
        }

        return false;
    }

    protected boolean startsOnOrBefore(TrackBack left, TrackBack right) {
        if (left.getStartLine() > right.getStartLine()) {
            return false;
        }

        if (left.getStartLine() < right.getStartLine()) {
            return true;
        }

        // Same line
        return left.getStartChar() <= right.getStartChar();
    }

    protected boolean endsOnOrAfter(TrackBack left, TrackBack right) {
        if (left.getEndLine() < right.getEndLine()) {
            return false;
        }

        if (left.getEndLine() > right.getEndLine()) {
            return true;
        }

        // Same line
        return left.getEndChar() >= right.getEndChar();
    }

}
