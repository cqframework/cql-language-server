package org.opencds.cqf.cql.debug

import org.eclipse.lsp4j.debug.CompletionItemType
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Period
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.opencds.cqf.cql.engine.runtime.Interval
import org.opencds.cqf.cql.engine.runtime.Tuple
import org.opencds.cqf.cql.engine.runtime.Integer as CqlInteger

class DebugConsoleCompletionTest {
    @Nested
    inner class FindCompletionTarget {
        /** `|` in [input] marks the cursor. */
        private fun target(input: String): CompletionTarget? {
            val cursor = input.indexOf('|')
            return findCompletionTarget(input.removeRange(cursor, cursor + 1), cursor)
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("org.opencds.cqf.cql.debug.DebugConsoleCompletionTest#completableCases")
        fun `finds the trailing word`(
            input: String,
            rootText: String?,
            prefix: String,
            replaceStart: Int,
        ) {
            assertEquals(CompletionTarget(rootText, prefix, replaceStart), target(input))
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("org.opencds.cqf.cql.debug.DebugConsoleCompletionTest#notCompletableCases")
        fun `returns null when nothing is completable`(input: String) {
            assertNull(target(input))
        }

        @Test
        fun `cursor past the end is clamped to the line`() {
            assertEquals(CompletionTarget(null, "Ind", 0), findCompletionTarget("Ind", 99))
        }
    }

    @Nested
    inner class Completer {
        private val completer = DebugConsoleCompleter(VariableResolver())

        private fun encounter(): Encounter =
            Encounter().also {
                it.status = Encounter.EncounterStatus.FINISHED
                it.period = Period().setStartElement(DateTimeType("2024-01-01")).setEndElement(DateTimeType("2024-01-02"))
            }

        private fun registry(): RuntimeValueRegistry =
            RuntimeValueRegistry().also {
                it.putStackVariable("E", encounter(), "FHIR.Encounter")
                it.putDefine("Qualifying Encounters", listOf(encounter(), encounter()), "List<FHIR.Encounter>", VersionedIdentifier().withId("Lib"))
                it.putDefine("Count", 2, "System.Integer", VersionedIdentifier().withId("Lib"))
                it.putDefine("Range", Interval(CqlInteger(1), true, CqlInteger(10), true), "Interval<System.Integer>", null)
                it.putDefine("Row", Tuple().withElements(mutableMapOf("id" to CqlInteger(1), "first name" to CqlInteger(2))), "Tuple", null)
                it.putDefine("Missing", null, null, null)
                it.loadContextResource("Patient", mapOf("id" to "p1"), "FHIR.Patient")
            }

        @Test
        fun `top-level lists locals, defines, context in scoping order`() {
            val items = completer.complete(CompletionTarget(null, "", 0), registry())
            val sorted = items.sortedBy { it.sortText }
            assertEquals("E", sorted.first().label)
            assertEquals(CompletionItemType.VARIABLE, sorted.first().type)
            assertEquals("Patient", sorted.last().label)
            assertEquals(CompletionItemType.CLASS, sorted.last().type)
            val defines = items.filter { it.type == CompletionItemType.VALUE }.map { it.label }.toSet()
            assertEquals(setOf("\"Qualifying Encounters\"", "Count", "Range", "Row", "Missing"), defines)
        }

        @Test
        fun `top-level detail shows category, library and type`() {
            val count = completer.complete(CompletionTarget(null, "Co", 0), registry()).single { it.label == "Count" }
            assertEquals("define (Lib): System.Integer", count.detail)
            val e = completer.complete(CompletionTarget(null, "", 0), registry()).single { it.label == "E" }
            assertEquals("local: FHIR.Encounter", e.detail)
        }

        @Test
        fun `names are quoted when not simple identifiers or when the user opened a quote`() {
            val items = completer.complete(CompletionTarget(null, "\"Co", 4), registry())
            assertEquals("\"Count\"", items.single { it.label.contains("Count") }.text)
            assertEquals("\"Qualifying Encounters\"", items.single { it.label.contains("Qualifying") }.text)
            val backtick = completer.complete(CompletionTarget(null, "`Q", 0), registry())
            assertEquals("`Qualifying Encounters`", backtick.single { it.label.contains("Qualifying") }.text)
        }

        @Test
        fun `items replace exactly the typed prefix`() {
            val item = completer.complete(CompletionTarget(null, "Co", 7), registry()).first()
            assertEquals(7, item.start)
            assertEquals(2, item.length)
        }

        @Test
        fun `a stack variable shadows a define of the same name`() {
            val reg = registry().also { it.putDefine("E", 1, "System.Integer", null) }
            val es = completer.complete(CompletionTarget(null, "", 0), reg).filter { it.label == "E" }
            assertEquals(1, es.size)
            assertEquals(CompletionItemType.VARIABLE, es.single().type)
        }

        @Test
        fun `members of a FHIR resource`() {
            val labels = completer.complete(CompletionTarget("E", "", 2), registry()).map { it.label }
            assertTrue(labels.containsAll(listOf("period", "status", "subject")), "got $labels")
            assertTrue(completer.complete(CompletionTarget("E", "", 2), registry()).all { it.type == CompletionItemType.PROPERTY })
        }

        @Test
        fun `members of a nested property path`() {
            val labels = completer.complete(CompletionTarget("E.period", "st", 9), registry()).map { it.label }
            assertTrue(labels.containsAll(listOf("start", "end")), "got $labels")
        }

        @Test
        fun `members of a quoted list define are the union of item children`() {
            val labels = completer.complete(CompletionTarget("\"Qualifying Encounters\"", "", 24), registry()).map { it.label }
            assertTrue(labels.contains("period"), "got $labels")
        }

        @Test
        fun `members of an indexed root`() {
            val labels = completer.complete(CompletionTarget("\"Qualifying Encounters\"[0]", "", 27), registry()).map { it.label }
            assertTrue(labels.contains("period"), "got $labels")
        }

        @Test
        fun `members of an interval`() {
            val labels = completer.complete(CompletionTarget("Range", "", 6), registry()).map { it.label }
            assertEquals(listOf("high", "highClosed", "low", "lowClosed"), labels)
        }

        @Test
        fun `members of a tuple quote non-identifier keys`() {
            val labels = completer.complete(CompletionTarget("Row", "", 4), registry()).map { it.label }
            assertEquals(listOf("\"first name\"", "id"), labels)
        }

        @Test
        fun `no members for null values, primitives, or unknown roots`() {
            assertTrue(completer.complete(CompletionTarget("Missing", "", 8), registry()).isEmpty())
            assertTrue(completer.complete(CompletionTarget("Count", "", 6), registry()).isEmpty())
            assertTrue(completer.complete(CompletionTarget("Nope", "", 5), registry()).isEmpty())
            assertTrue(completer.complete(CompletionTarget("E.nope", "", 7), registry()).isEmpty())
        }
    }

    companion object {
        @JvmStatic
        fun completableCases(): List<Arguments> =
            listOf(
                Arguments.of("|", null, "", 0),
                Arguments.of("Ind|", null, "Ind", 0),
                Arguments.of("IndexPCP.|", "IndexPCP", "", 9),
                Arguments.of("IndexPCP.per|", "IndexPCP", "per", 9),
                Arguments.of("E.period.st|", "E.period", "st", 9),
                Arguments.of("\"Qualifying Enc|", null, "\"Qualifying Enc", 0),
                Arguments.of("\"Qualifying Encounters\".|", "\"Qualifying Encounters\"", "", 24),
                Arguments.of("\"A.B\".per|", "\"A.B\"", "per", 6),
                Arguments.of("name[0].|", "name[0]", "", 8),
                Arguments.of("E.period ends before IndexPCP.per|", "IndexPCP", "per", 30),
                Arguments.of("foo(IndexPCP.|", "IndexPCP", "", 13),
                Arguments.of("exists Ind|", null, "Ind", 7),
                Arguments.of("Ind| + 1", null, "Ind", 0),
                Arguments.of("'a.b' + Ind|", null, "Ind", 8),
                Arguments.of("x = \"Qual|", null, "\"Qual", 4),
                Arguments.of("[Enc|", null, "Enc", 1),
            )

        @JvmStatic
        fun notCompletableCases(): List<Arguments> =
            listOf(
                Arguments.of("'abc.|"),
                Arguments.of("@12:5|"),
                Arguments.of("name[0]|"),
                Arguments.of("1.|"),
                Arguments.of("5 + 1|"),
                Arguments.of(".|"),
            )
    }
}
