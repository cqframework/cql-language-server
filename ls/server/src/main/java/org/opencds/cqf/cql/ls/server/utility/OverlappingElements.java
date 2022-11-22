package org.opencds.cqf.cql.ls.server.utility;

import org.cqframework.cql.elm.tracking.TrackBack;
import org.hl7.elm.r1.Element;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class OverlappingElements {

    public static Element getMostSpecificElement(List<Element> elements) {
        if (elements.size() == 0) {
            return null;
        }

        Comparator<Element> compareElements = (o1, o2) -> {
            if (o1.getTrackbacks().size() == 0) {
                return 1;
            }

            if (o2.getTrackbacks().size() == 0) {
                return -1;
            }

            TrackBack trackBackOne = o1.getTrackbacks().get(0);
            TrackBack trackBackTwo = o2.getTrackbacks().get(0);

            if (trackBackOne.getStartLine() == trackBackTwo.getStartLine()
                    && trackBackOne.getStartChar() == trackBackTwo.getStartChar()
                    && trackBackOne.getEndLine() == trackBackTwo.getEndLine()
                    && trackBackOne.getEndChar() == trackBackTwo.getEndChar()
            ) {
                return 0;
            }

            if (trackBackOne.getStartLine() < trackBackTwo.getStartLine()) {
                return 1;
            }

            if (trackBackOne.getStartLine() == trackBackTwo.getStartLine() && trackBackOne.getStartChar() < trackBackTwo.getStartChar()) {
                return 1;
            }

            if (trackBackOne.getEndLine() > trackBackTwo.getEndLine()) {
                return 1;
            }

            if (trackBackOne.getEndLine() == trackBackTwo.getEndLine() && trackBackOne.getEndChar() > trackBackTwo.getEndChar()) {
                return 1;
            }

            return -1;
        };

        Collections.sort(elements, compareElements);

        return elements.get(0);
    }
}
