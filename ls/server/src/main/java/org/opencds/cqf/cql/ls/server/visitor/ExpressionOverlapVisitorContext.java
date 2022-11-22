package org.opencds.cqf.cql.ls.server.visitor;

import org.cqframework.cql.elm.tracking.TrackBack;
import org.eclipse.lsp4j.Position;
import org.hl7.elm.r1.Element;

import java.util.ArrayList;
import java.util.List;

public class ExpressionOverlapVisitorContext {
    private List<Element> overlappingElements = new ArrayList<>();

    private Position searchPosition;

    public ExpressionOverlapVisitorContext(Position searchPosition) {
        this.searchPosition = searchPosition;
    }

    public List<Element> getOverlappingElements () {
        return this.overlappingElements;
    }

    public void addOverlappingElement (Element el) {
        this.overlappingElements.add(el);
    }

    protected boolean doesElementOverlapSearchPosition(Element elm) {
        for (TrackBack tb : elm.getTrackbacks()) {
            if (this.doesTrackBackOverlapSearchPosition(tb)) {
                return true;
            }
        }
        return false;
    }

    protected boolean doesTrackBackOverlapSearchPosition (TrackBack trackback) {
        // If search line is before trackback start return false
        if (this.searchPosition.getLine() < trackback.getStartLine()) {
            return false;
        }

        // If search on same line as trackback start, search character must come on or after elementRange start
        if (this.searchPosition.getLine() == trackback.getStartLine() && this.searchPosition.getCharacter() < trackback.getStartChar()) {
            return false;
        }

        // If search line is after trackback end return false
        if (this.searchPosition.getLine() > trackback.getEndLine()) {
            return false;
        }

        // If search on same line as trackback end, search character must come on or after elementRange start
        if (this.searchPosition.getLine() == trackback.getEndLine() && this.searchPosition.getCharacter() > trackback.getEndChar()) {
            return false;
        }

        return true;
    }
}
