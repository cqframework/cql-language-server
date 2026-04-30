package org.opencds.cqf.cql.ls.server.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.service.TestContentService

/**
 * Unit tests for [CqlEvaluator]. Exercises the private parameter-coercion logic indirectly
 * through the public [CqlEvaluator.evaluate] entry point, using [TestContentService] to
 * resolve CQL libraries from the classpath.
 *
 * Parameters not declared in the target CQL library are silently ignored by the engine, so
 * "coercion-only" tests can send any parameter to `One.cql` and just assert that the call
 * succeeds without an exception or an `Error` expression.
 */
class CqlEvaluatorTest {
    companion object {
        private lateinit var contentService: TestContentService
        private lateinit var igContextManager: IgContextManager

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            contentService = TestContentService()
            igContextManager = IgContextManager(contentService)
        }
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    private fun evaluate(
        libraryName: String,
        vararg params: ParameterRequest,
    ): ExecuteCqlResponse {
        val request =
            ExecuteCqlRequest(
                fhirVersion = "R4",
                rootDir = null,
                optionsPath = null,
                libraries =
                    listOf(
                        LibraryRequest(
                            libraryName = libraryName,
                            libraryUri = "file:///any/path",
                            libraryVersion = null,
                            terminologyUri = null,
                            model = null,
                            context = null,
                            parameters = params.toList(),
                        ),
                    ),
            )
        return CqlEvaluator.evaluate(request, contentService, igContextManager)
    }

    // ------------------------------------------------------------------
    // NullResult — verifies tempConvert(null) == "null"
    // ------------------------------------------------------------------

    @Test
    fun `NullResult library returns NullDef expression with value null`() {
        val response = evaluate("NullResult")
        assertEquals(1, response.results.size)
        val expr = response.results[0].expressions.find { it.name == "NullDef" }
        assertNotNull(expr, "Expected 'NullDef' expression in results")
        assertEquals("null", expr!!.value)
    }

    // ------------------------------------------------------------------
    // Batch ordering — different library names → different batches
    // ------------------------------------------------------------------

    @Test
    fun `two different libraries in one request preserve insertion order`() {
        val request =
            ExecuteCqlRequest(
                fhirVersion = "R4",
                rootDir = null,
                optionsPath = null,
                libraries =
                    listOf(
                        LibraryRequest("One", "file:///any/path", null, null, null, null, emptyList()),
                        LibraryRequest("NullResult", "file:///any/path", null, null, null, null, emptyList()),
                    ),
            )
        val response = CqlEvaluator.evaluate(request, contentService, igContextManager)
        assertEquals(2, response.results.size)
        assertEquals("One", response.results[0].libraryName)
        assertEquals("NullResult", response.results[1].libraryName)
    }

    // ------------------------------------------------------------------
    // Scalar coercion — valid inputs
    // ------------------------------------------------------------------

    @Test
    fun `Integer parameter coerces without exception`() {
        val response = evaluate("One", ParameterRequest("Unused", "Integer", "42"))
        assertEquals(1, response.results.size)
        assertFalse(response.results[0].expressions.any { it.name == "Error" })
    }

    @Test
    fun `Decimal parameter coerces without exception`() {
        val response = evaluate("One", ParameterRequest("Unused", "Decimal", "3.14"))
        assertEquals(1, response.results.size)
        assertFalse(response.results[0].expressions.any { it.name == "Error" })
    }

    @Test
    fun `Boolean parameter coerces without exception`() {
        val response = evaluate("One", ParameterRequest("Unused", "Boolean", "true"))
        assertEquals(1, response.results.size)
        assertFalse(response.results[0].expressions.any { it.name == "Error" })
    }

    @Test
    fun `DateTime parameter coerces without exception`() {
        val response = evaluate("One", ParameterRequest("Unused", "DateTime", "@2024-01-15T10:30:00.000Z"))
        assertEquals(1, response.results.size)
        assertFalse(response.results[0].expressions.any { it.name == "Error" })
    }

    @Test
    fun `Date parameter coerces without exception`() {
        val response = evaluate("One", ParameterRequest("Unused", "Date", "@2024-01-15"))
        assertEquals(1, response.results.size)
        assertFalse(response.results[0].expressions.any { it.name == "Error" })
    }

    @Test
    fun `Time parameter coerces without exception`() {
        val response = evaluate("One", ParameterRequest("Unused", "Time", "@T14:30:00.000"))
        assertEquals(1, response.results.size)
        assertFalse(response.results[0].expressions.any { it.name == "Error" })
    }

    @Test
    fun `Quantity parameter coerces without exception`() {
        val response = evaluate("One", ParameterRequest("Unused", "Quantity", "5 'mg'"))
        assertEquals(1, response.results.size)
        assertFalse(response.results[0].expressions.any { it.name == "Error" })
    }

    // ------------------------------------------------------------------
    // Scalar coercion — invalid inputs fall back to String (no exception)
    // ------------------------------------------------------------------

    @Test
    fun `invalid Integer falls back to String without exception`() {
        val response = evaluate("One", ParameterRequest("Unused", "Integer", "not-a-number"))
        assertEquals(1, response.results.size)
    }

    @Test
    fun `invalid Decimal falls back to String without exception`() {
        val response = evaluate("One", ParameterRequest("Unused", "Decimal", "not-a-decimal"))
        assertEquals(1, response.results.size)
    }

    @Test
    fun `invalid Boolean falls back to String without exception`() {
        // toBooleanStrictOrNull only accepts "true"/"false" (case-insensitive) — "maybe" must fall back
        val response = evaluate("One", ParameterRequest("Unused", "Boolean", "maybe"))
        assertEquals(1, response.results.size)
    }

    @Test
    fun `invalid DateTime falls back to String without exception`() {
        val response = evaluate("One", ParameterRequest("Unused", "DateTime", "bad-date"))
        assertEquals(1, response.results.size)
    }

    @Test
    fun `invalid Date falls back to String without exception`() {
        val response = evaluate("One", ParameterRequest("Unused", "Date", "bad-date"))
        assertEquals(1, response.results.size)
    }

    @Test
    fun `invalid Time falls back to String without exception`() {
        val response = evaluate("One", ParameterRequest("Unused", "Time", "bad-time"))
        assertEquals(1, response.results.size)
    }

    @Test
    fun `invalid Quantity falls back to String without exception`() {
        // Missing space between value and unit — regex does not match → IllegalArgumentException → String fallback
        val response = evaluate("One", ParameterRequest("Unused", "Quantity", "5mg"))
        assertEquals(1, response.results.size)
    }

    @Test
    fun `unknown parameter type falls back to String without exception`() {
        val response = evaluate("One", ParameterRequest("Unused", "Frobnicator", "some-value"))
        assertEquals(1, response.results.size)
    }

    // ------------------------------------------------------------------
    // Interval<Date> coercion
    // ------------------------------------------------------------------

    @Test
    fun `Interval_Date parameter is coerced and evaluated correctly`() {
        // WithDateParam.cql declares `parameter "Date Range" Interval<Date>` and defines
        // `"Range Start": start of "Date Range"`. A native CqlInterval must reach the engine —
        // passing a raw String would cause a type mismatch error expression.
        val response =
            evaluate(
                "WithDateParam",
                ParameterRequest("Date Range", "Interval<Date>", "Interval[@2024-01-01, @2025-01-01)"),
            )
        assertEquals(1, response.results.size)
        assertFalse(
            response.results[0].expressions.any { it.name == "Error" },
            "Unexpected Error expression: ${response.results[0].expressions}",
        )
        assertNotNull(
            response.results[0].expressions.find { it.name == "Range Start" },
            "Expected 'Range Start' expression in results",
        )
    }

    @Test
    fun `Interval_Date with open endpoints is coerced without exception`() {
        // Interval(low, high) — both endpoints open (exclusive)
        val response =
            evaluate(
                "One",
                ParameterRequest("Unused", "Interval<Date>", "Interval(@2024-01-01, @2025-01-01)"),
            )
        assertEquals(1, response.results.size)
    }

    @Test
    fun `Interval_DateTime with null low endpoint is coerced without exception`() {
        // The interval parser recognises the keyword "null" and produces a null endpoint
        val response =
            evaluate(
                "One",
                ParameterRequest("Unused", "Interval<DateTime>", "Interval[null, @2025-01-01T00:00:00.000Z)"),
            )
        assertEquals(1, response.results.size)
    }

    @Test
    fun `malformed Interval_Date literal falls back gracefully without throwing`() {
        val response =
            evaluate(
                "One",
                ParameterRequest("Unused", "Interval<Date>", "NOT_AN_INTERVAL"),
            )
        assertEquals(1, response.results.size)
    }

    @Test
    fun `Interval_Date default is reported as usedDefaultParameter when not supplied`() {
        // WithDateParam.cql declares a default — omitting the parameter must surface it in
        // usedDefaultParameters with source "default".
        val response = evaluate("WithDateParam")
        assertEquals(1, response.results.size)
        val defaultParam = response.results[0].usedDefaultParameters.find { it.name == "Date Range" }
        assertNotNull(
            defaultParam,
            "Expected 'Date Range' in usedDefaultParameters, got: ${response.results[0].usedDefaultParameters}",
        )
        assertEquals("default", defaultParam!!.source)
        assertFalse(defaultParam.value.isBlank(), "Expected non-blank resolved value for default Interval<Date>")
    }

    // ------------------------------------------------------------------
    // tempConvert — Iterable branch
    // ------------------------------------------------------------------

    @Test
    fun `tempConvert renders list expression as bracketed comma-separated values`() {
        // ListResult.cql defines `"Items": {1, 2, 3}` — the engine returns a java.util.List whose
        // elements each go through tempConvert individually, producing "[1, 2, 3]".
        val response = evaluate("ListResult")
        assertEquals(1, response.results.size)
        val expr = response.results[0].expressions.find { it.name == "Items" }
        assertNotNull(expr, "Expected 'Items' expression in results")
        assertEquals("[1, 2, 3]", expr!!.value)
    }

    // ------------------------------------------------------------------
    // parseCqlIntervalValue — remaining endpoint branches
    // ------------------------------------------------------------------

    @Test
    fun `Interval_DateTime with null high endpoint is coerced without exception`() {
        // Tests the null-endpoint branch for groupValues[3] (high endpoint position).
        // Existing null-endpoint test covers groupValues[2] (low endpoint).
        val response =
            evaluate(
                "One",
                ParameterRequest("Unused", "Interval<DateTime>", "Interval[@2024-01-01T00:00:00.000Z, null]"),
            )
        assertEquals(1, response.results.size)
    }

    @Test
    fun `Interval_DateTime with bad endpoint inside valid structure falls back without exception`() {
        // The outer interval regex matches, but parseCqlDateTimeValue("@bad") throws inside
        // parseEndpoint → caught → warning logged → endpoint falls back to the raw string.
        // One.cql ignores the parameter, so no Error expression propagates.
        val response =
            evaluate(
                "One",
                ParameterRequest("Unused", "Interval<DateTime>", "Interval[@2024-01-01T00:00:00.000Z, @bad)"),
            )
        assertEquals(1, response.results.size)
    }

    // ------------------------------------------------------------------
    // quantityLiteralRegex — decimal branch
    // ------------------------------------------------------------------

    @Test
    fun `Quantity with decimal value coerces without exception`() {
        // Tests the (?:\\.\\d+)? group of quantityLiteralRegex — only integer quantities were
        // previously exercised (e.g. "5 'mg'").
        val response = evaluate("One", ParameterRequest("Unused", "Quantity", "1.5 'd'"))
        assertEquals(1, response.results.size)
        assertFalse(response.results[0].expressions.any { it.name == "Error" })
    }
}
