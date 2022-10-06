package org.opencds.cqf.cql.ls.server.visitor;

import org.cqframework.cql.elm.visiting.ElmBaseLibraryVisitor;
import org.eclipse.lsp4j.Position;
import org.hl7.elm.r1.Element;
import org.hl7.elm.r1.ExpressionDef;
import org.hl7.elm.r1.ExpressionRef;
import org.hl7.elm.r1.FunctionDef;
import org.hl7.elm.r1.Retrieve;
import org.opencds.cqf.cql.ls.server.utility.Elements;

public class ExpressionTrackBackVisitor extends ElmBaseLibraryVisitor<Element, Position> {

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
    public Element visitExpressionDef(ExpressionDef elm, Position context) {
        if (elm instanceof FunctionDef) {
            return visitFunctionDef((FunctionDef) elm, context);
        }
        Element childResult = visitChildren(elm, context);
        return aggregateResult(Elements.containsPosition(elm, context) ? elm : null, childResult);
    }

    @Override
    public Element visitRetrieve(Retrieve retrieve, Position context) {
        if (Elements.containsPosition(retrieve, context)) {
            return retrieve;
        }

        return null;
    }

    @Override
    public Element visitExpressionRef(ExpressionRef elm, Position context) {
        if (Elements.containsPosition(elm, context)) {
            return elm;
        }

        return null;
    }
}
