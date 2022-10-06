package org.opencds.cqf.cql.ls.server.utility;

import org.cqframework.cql.elm.tracking.TrackBack;
import org.eclipse.lsp4j.Position;
import org.hl7.elm.r1.Element;

public class Elements {

    private Elements() {}

    public static boolean containsTrackBack(Element elm, TrackBack context) {
        for (TrackBack tb : elm.getTrackbacks()) {
            if (TrackBacks.containsTrackBack(tb, context)) {
                return true;
            }
        }

        return false;
    }

    public static boolean containsPosition(Element elm, Position position) {
        for (TrackBack tb : elm.getTrackbacks()) {
            if (TrackBacks.containsPosition(tb, position)) {
                return true;
            }
        }

        return false;
    }
}
