package org.opencds.cqf.cql.ls.server.visitor

import org.antlr.v4.kotlinruntime.CharStreams
import org.antlr.v4.kotlinruntime.CommonTokenStream
import org.cqframework.cql.gen.cqlLexer
import org.cqframework.cql.gen.cqlParser
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CqlStepPositionCollectorTest {
    private fun parse(cql: String): cqlParser.LibraryContext {
        val lexer = cqlLexer(CharStreams.fromString(cql))
        val parser = cqlParser(CommonTokenStream(lexer))
        return parser.library()
    }

    @Test
    fun queryWithWhere() {
        val cql =
            """
            library Test
            define "Qualifying Encounters":
                ([Encounter: "Encounter to Screen for Depression"]
                    union [Encounter: "Physical Therapy Evaluation"]
                    union [Encounter: "Telephone Visits"]) QualifyingEncounter
                    where QualifyingEncounter.period during day of "Measurement Period"
                      and QualifyingEncounter.status = 'finished'
            """.trimIndent()
        val lines = CqlStepPositionCollector.collect(parse(cql))
        assertTrue(6 in lines) // where clause line
    }

    @Test
    fun queryWithReturn() {
        val cql =
            """
            library Test
            define "With Return":
                [Encounter] E
                    return E.period
            """.trimIndent()
        val lines = CqlStepPositionCollector.collect(parse(cql))
        assertTrue(4 in lines) // return clause line
    }

    @Test
    fun queryWithLet() {
        val cql =
            """
            library Test
            define "With Let":
                [Encounter] E
                    let X: E.status
            """.trimIndent()
        val lines = CqlStepPositionCollector.collect(parse(cql))
        assertTrue(4 in lines) // let clause line
    }

    @Test
    fun queryWithSortBy() {
        val cql =
            """
            library Test
            define "With Sort":
                [Encounter] E
                    sort by E.period
            """.trimIndent()
        val lines = CqlStepPositionCollector.collect(parse(cql))
        assertTrue(4 in lines) // sort by clause line
    }

    @Test
    fun queryWithWithAndWithout() {
        val cql =
            """
            library Test
            define "With and Without":
                [Encounter] E
                    with [Observation] O
                        such that O.status = 'final'
                    without [Procedure] P
                        such that P.status = 'completed'
            """.trimIndent()
        val lines = CqlStepPositionCollector.collect(parse(cql))
        // The such-that expression lines inside with/without are collected
        assertTrue(5 in lines) // with such-that expression line
        assertTrue(7 in lines) // without such-that expression line
    }

    @Test
    fun queryNestedInsideLet() {
        val cql =
            """
            library Test
            define "Nested in Let":
                [Encounter] E
                    let X:
                        ( [Observation] O
                            where O.status = 'final' )
            """.trimIndent()
        val lines = CqlStepPositionCollector.collect(parse(cql))
        // let clause captures the expression start line (the parenthesized expression)
        assertTrue(5 in lines) // let expression line
        assertTrue(6 in lines) // inner query where clause line
    }

    @Test
    fun queryNestedInsideReturn() {
        val cql =
            """
            library Test
            define "Nested in Return":
                [Encounter] E
                    return
                        ( [Observation] O
                            where O.status = 'final' )
            """.trimIndent()
        val lines = CqlStepPositionCollector.collect(parse(cql))
        // return clause captures the expression start line
        assertTrue(5 in lines) // return expression line
        assertTrue(6 in lines) // inner query where clause line
    }

    @Test
    fun plainExpressionNoQuery() {
        val cql =
            """
            library Test
            define "Simple":
                1 + 2
            """.trimIndent()
        val lines = CqlStepPositionCollector.collect(parse(cql))
        assertTrue(3 in lines) // body expression start line
        assertFalse(lines.any { it == 1 }) // library header not included
    }

    @Test
    fun standaloneRetrieve() {
        val cql =
            """
            library Test
            define "MyObs":
                [Observation]
            """.trimIndent()
        val lines = CqlStepPositionCollector.collect(parse(cql))
        assertTrue(3 in lines) // retrieve line
    }

    @Test
    fun multipleDefinesOnlyCollectsOwnLines() {
        val cql =
            """
            library Test
            define "A": 1
            define "B":
                [Encounter] E
                    where E.status = 'finished'
            """.trimIndent()
        val lines = CqlStepPositionCollector.collect(parse(cql))
        assertTrue(2 in lines) // first define body start
        assertTrue(4 in lines) // second define where clause
        assertFalse(1 in lines) // library header not included
    }

    @Test
    fun aggregateClause() {
        val cql =
            """
            library Test
            define "With Aggregate":
                [Encounter] E
                    aggregate X: X + 1
            """.trimIndent()
        val lines = CqlStepPositionCollector.collect(parse(cql))
        assertTrue(4 in lines) // aggregate clause line
    }
}
