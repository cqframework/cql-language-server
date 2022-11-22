package org.opencds.cqf.cql.ls.server.visitor;

import org.cqframework.cql.elm.tracking.TrackBack;
import org.cqframework.cql.elm.tracking.Trackable;
import org.cqframework.cql.elm.visiting.ElmBaseLibraryVisitor;
import org.hl7.elm.r1.Element;

import java.util.List;

public class ExpressionOverlapVisitor  extends ElmBaseLibraryVisitor<Void, ExpressionOverlapVisitorContext> {

    @Override
    protected Void defaultResult(Trackable elm, ExpressionOverlapVisitorContext context) {
        if (elm instanceof Element) {
            Element element = (Element) elm;
            if (context.doesElementOverlapSearchPosition(element)) {
                context.addOverlappingElement(element);
            }
        }

        return super.defaultResult(elm, context);
    }

}
