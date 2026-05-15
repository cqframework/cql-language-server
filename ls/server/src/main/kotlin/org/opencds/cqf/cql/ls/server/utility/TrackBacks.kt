package org.opencds.cqf.cql.ls.server.utility

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.util.Ranges
import kotlin.math.max

/**
 * Utilities for working with ELM element locator strings.
 *
 * ELM elements carry their source position in [Element.locator] as a string with format
 * `"startLine:startChar-endLine:endChar"` (1-indexed), optionally prefixed with a library
 * name: `"LibraryName:startLine:startChar-endLine:endChar"`.
 *
 * Position arithmetic (TrackBack is 1-indexed; LSP [Position] is 0-indexed):
 * - start: `line-1`, `max(char-1, 0)`
 * - end:   `line-1`, `char` (no -1 on end char — LSP range end is exclusive)
 */
object TrackBacks {
    /**
     * Matches the trailing `digits:digits-digits:digits` portion of a locator string,
     * ignoring any leading library prefix.
     */
    private val LOCATOR_REGEX = Regex("""(\d+):(\d+)-(\d+):(\d+)$""")

    fun toRange(locator: String): Range? {
        val m = LOCATOR_REGEX.find(locator) ?: return null
        val (sl, sc, el, ec) = m.destructured
        return Range(
            Position(sl.toInt() - 1, max(sc.toInt() - 1, 0)),
            Position(el.toInt() - 1, ec.toInt()),
        )
    }

    fun containsPosition(
        locator: String,
        p: Position,
    ): Boolean = toRange(locator)?.let { Ranges.containsPosition(it, p) } ?: false
}
