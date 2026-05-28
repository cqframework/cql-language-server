package org.opencds.cqf.cql.ls.server.utility

import org.eclipse.lsp4j.Position
import org.hl7.elm.r1.ExpressionDef
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ElementsTest {
    @Test
    fun containsPosition_elementWithLocator_returnsTrue() {
        val elm = ExpressionDef().apply { locator = "1:0-2:10" }
        assertTrue(Elements.containsPosition(elm, Position(0, 5)))
    }

    @Test
    fun containsPosition_elementWithoutLocator_returnsFalse() {
        val elm = ExpressionDef().apply { locator = null }
        assertFalse(Elements.containsPosition(elm, Position(0, 0)))
    }

    @Test
    fun containsPosition_outsideLocator_returnsFalse() {
        val elm = ExpressionDef().apply { locator = "1:0-1:10" }
        assertFalse(Elements.containsPosition(elm, Position(0, 15)))
    }
}
