package org.opencds.cqf.cql.ls.server.utility

import org.eclipse.lsp4j.Position
import org.hl7.elm.r1.Element

object Elements {
    fun containsPosition(
        elm: Element,
        p: Position,
    ): Boolean {
        val locator = elm.locator ?: return false
        return TrackBacks.containsPosition(locator, p)
    }
}
