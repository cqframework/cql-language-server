package org.opencds.cqf.cql.ls.server.utility;

import org.cqframework.cql.elm.tracking.TrackBack;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.util.Ranges;

public class TrackBacks {

    private TrackBacks() {}

    public static Range toRange(TrackBack trackBack) {
        // The Language server API assumes 0 based indices and an exclusive range
        return new Range(
                new Position(trackBack.getStartLine() - 1,
                        Math.max(trackBack.getStartChar() - 1, 0)),
                new Position(trackBack.getEndLine() - 1, trackBack.getEndChar()));
    }

    public static boolean containsTrackBack(TrackBack bigger, TrackBack smaller) {
        var range1 = toRange(bigger);
        var range2 = toRange(smaller);

        return Ranges.containsRange(range1, range2);
    }

    public static boolean containsPosition(TrackBack trackBack, Position p) {
        Range range = toRange(trackBack);
        return Ranges.containsPosition(range, p);
    }
}
