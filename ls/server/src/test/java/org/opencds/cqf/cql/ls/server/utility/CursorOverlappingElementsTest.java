package org.opencds.cqf.cql.ls.server.utility;

import org.cqframework.cql.elm.tracking.TrackBack;
import org.eclipse.lsp4j.Position;
import org.hl7.elm.r1.Element;
import org.hl7.elm.r1.ExpressionRef;
import org.hl7.elm.r1.Library;
import org.hl7.elm.r1.Literal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CursorOverlappingElementsTest {
    @Test
    public void shouldSortElementsFromSmallestToLargest() throws Exception {

        List<Element> overlappingElements =  new ArrayList<>();

        Library lib = new Library();

        Literal one = new Literal();
        one.getTrackbacks().add(new TrackBack(lib.getIdentifier(), 1, 1, 5, 10));

        Literal two = new Literal();
        two.getTrackbacks().add(new TrackBack(lib.getIdentifier(), 1, 1, 4, 10));

        Literal three = new Literal();
        three.getTrackbacks().add(new TrackBack(lib.getIdentifier(), 1, 5, 4, 10));

        Literal four = new Literal();
        four.getTrackbacks().add(new TrackBack(lib.getIdentifier(), 2, 5, 4, 10));

        Literal five = new Literal();
        five.getTrackbacks().add(new TrackBack(lib.getIdentifier(), 3, 5, 3, 10));

        Literal six = new Literal();
        six.getTrackbacks().add(new TrackBack(lib.getIdentifier(), 3, 5, 3, 8));

        // Place them in wrong order
        overlappingElements.add(two);
        overlappingElements.add(five);
        overlappingElements.add(six);
        overlappingElements.add(one);
        overlappingElements.add(three);
        overlappingElements.add(four);

        List<Element> sortedElements = CursorOverlappingElements.sortElementsFromSmallestToLargest(overlappingElements);
        assertEquals(sortedElements.get(0), one);
        assertEquals(sortedElements.get(1), two);
        assertEquals(sortedElements.get(2), three);
        assertEquals(sortedElements.get(3), four);
        assertEquals(sortedElements.get(4), five);
        assertEquals(sortedElements.get(5), six);
    }
}
