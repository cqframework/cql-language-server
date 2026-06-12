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

    // ---- collectBreakpointableLines tests ----

    @Test
    fun breakpointableLinesExcludesBlankLines() {
        val cql =
            """
            library Test
            define "A": 1

            define "B": 2
            """.trimIndent()
        val lines = CqlStepPositionCollector.collectBreakpointableLines(parse(cql))
        assertTrue(2 in lines)  // define A body
        assertTrue(4 in lines)  // define B body
        assertFalse(3 in lines) // blank line
    }

    @Test
    fun breakpointableLinesExcludesLibraryHeaders() {
        val cql =
            """
            library Test version '1.0.0'
            using FHIR version '4.0.1'
            include OtherLib called OL
            define "A": 1
            """.trimIndent()
        val lines = CqlStepPositionCollector.collectBreakpointableLines(parse(cql))
        assertFalse(1 in lines) // library header
        assertFalse(2 in lines) // using
        assertFalse(3 in lines) // include
        assertTrue(4 in lines)  // define body
    }

    @Test
    fun breakpointableLinesExcludesCodeAndParamDefinitions() {
        val cql =
            """
            library Test
            codesystem "SNOMED": 'http://snomed.info/sct'
            valueset "VS": 'http://example.org/fhir/ValueSet/VS'
            code "C": '123' from "SNOMED"
            concept "Con": { "C" }
            parameter "P" default 1
            context Patient
            define "A": 1
            """.trimIndent()
        val lines = CqlStepPositionCollector.collectBreakpointableLines(parse(cql))
        assertFalse(2 in lines) // codesystem
        assertFalse(3 in lines) // valueset
        assertFalse(4 in lines) // code
        assertFalse(5 in lines) // concept
        assertTrue(6 in lines)  // parameter default expression
        assertFalse(7 in lines) // context
        assertTrue(8 in lines)  // define body
    }

    @Test
    fun breakpointableLinesIncludesIfThenElseSubexpressions() {
        val cql =
            """
            library Test
            define "IfThenElse":
              if (1 + 2 = 4)
                then 5
              else 6
            """.trimIndent()
        val stepLines = CqlStepPositionCollector.collect(parse(cql))
        val bpLines = CqlStepPositionCollector.collectBreakpointableLines(parse(cql))
        // Both collect the define body start line
        assertTrue(3 in stepLines)
        assertTrue(3 in bpLines)
        // Only breakpointableLines includes the sub-expression lines
        assertTrue(4 in bpLines) // then branch
        assertTrue(5 in bpLines) // else branch
        assertFalse(4 in stepLines) // step collector excludes sub-expressions
        assertFalse(5 in stepLines)
    }

    @Test
    fun breakpointableLinesIncludesNestedFunctionArgs() {
        val cql =
            """
            library Test
            define "Nested":
              First(
                Second(
                  Third(1)
                )
              )
            """.trimIndent()
        val stepLines = CqlStepPositionCollector.collect(parse(cql))
        val bpLines = CqlStepPositionCollector.collectBreakpointableLines(parse(cql))
        assertTrue(3 in bpLines)  // First(
        assertTrue(4 in bpLines)  // Second(
        assertTrue(5 in bpLines)  // Third(1)
        assertTrue(3 in stepLines) // First( is also a step line
        assertFalse(4 in stepLines) // Second( is not a step line
    }

    @Test
    fun breakpointableLinesIncludesQueryClauseExpressions() {
        val cql =
            """
            library Test
            define "Query":
              [Encounter] E
                where E.status = 'finished'
                  and E.period during day of "Measurement Period"
                return
                  E.period
            """.trimIndent()
        val bpLines = CqlStepPositionCollector.collectBreakpointableLines(parse(cql))
        assertTrue(4 in bpLines)  // where expression (line 1)
        assertTrue(5 in bpLines)  // where expression (multi-line continuation)
        assertTrue(7 in bpLines)  // return expression
    }

    @Test
    fun breakpointableLinesExcludesDefineKeywordOnlyLine() {
        val cql =
            """
            library Test
            define "A":
              1 + 2
            """.trimIndent()
        val bpLines = CqlStepPositionCollector.collectBreakpointableLines(parse(cql))
        assertFalse(2 in bpLines) // define keyword line (no expression starts here)
        assertTrue(3 in bpLines)  // expression body line
    }

    @Test
    fun breakpointableLinesWithAndWithoutSubexpressions() {
        val cql =
            """
            library Test
            define "WithAndWithout":
              [Encounter] E
                with [Observation] O
                  such that O.status = 'final'
                    and O.code in "Some Valueset"
                without [Procedure] P
                  such that P.status = 'completed'
            """.trimIndent()
        val bpLines = CqlStepPositionCollector.collectBreakpointableLines(parse(cql))
        assertTrue(5 in bpLines)  // with such-that (line 1)
        assertTrue(6 in bpLines)  // with such-that (and continuation)
        assertTrue(8 in bpLines)  // without such-that
    }
}
