package org.opencds.cqf.cql.ls.server.command

import ca.uhn.fhir.context.FhirContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService
import org.opencds.cqf.fhir.cql.EvaluationSettings

class CqlEvaluatorTest {
    private val contentService = TestContentService()
    private val igContextManager = IgContextManager(contentService)
    private val libraryResolutionManager = LibraryResolutionManager(emptyList())

    // -------------------------------------------------------------------------
    // parseParameterValues (via reflection — it's private)
    // -------------------------------------------------------------------------

    private fun parseParameterValues(
        fhirContext: FhirContext,
        evaluationSettings: EvaluationSettings,
        parameters: List<ParameterRequest>,
    ): MutableMap<String?, Any?>? {
        val method =
            CqlEvaluator::class.java.getDeclaredMethod(
                "parseParameterValues",
                FhirContext::class.java,
                EvaluationSettings::class.java,
                List::class.java,
            )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(CqlEvaluator, fhirContext, evaluationSettings, parameters) as MutableMap<String?, Any?>?
    }

    private fun coerceDateLiterals(
        value: String,
        parameterType: String,
    ): String {
        val method =
            CqlEvaluator::class.java.getDeclaredMethod("coerceDateLiterals", String::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(CqlEvaluator, value, parameterType) as String
    }

    private val r4Context: FhirContext = FhirContext.forR4Cached()
    private val defaultSettings: EvaluationSettings = EvaluationSettings.getDefault()

    // -------------------------------------------------------------------------
    // coerceDateLiterals tests
    // -------------------------------------------------------------------------

    @Test
    fun `coerceDateLiterals leaves non-DateTime types unchanged`() {
        val input = "Interval[@2024-01-01, @2024-12-31)"
        assertEquals(input, coerceDateLiterals(input, "Interval<Date>"))
        assertEquals(input, coerceDateLiterals(input, "Integer"))
        assertEquals(input, coerceDateLiterals(input, "String"))
    }

    @Test
    fun `coerceDateLiterals appends T to bare date literals for DateTime type`() {
        assertEquals(
            "Interval[@2024-01-01T, @2024-12-31T)",
            coerceDateLiterals("Interval[@2024-01-01, @2024-12-31)", "Interval<DateTime>"),
        )
    }

    @Test
    fun `coerceDateLiterals does not double-append T to already-DateTime literals`() {
        assertEquals(
            "Interval[@2024-01-01T, @2024-12-31T)",
            coerceDateLiterals("Interval[@2024-01-01T, @2024-12-31T)", "Interval<DateTime>"),
        )
    }

    @Test
    fun `coerceDateLiterals coerces standalone DateTime parameter`() {
        assertEquals("@2024-06-15T", coerceDateLiterals("@2024-06-15", "DateTime"))
    }

    @Test
    fun `coerceDateLiterals preserves full DateTime literals with time`() {
        val full = "@2024-01-01T00:00:00.0"
        assertEquals(full, coerceDateLiterals(full, "Interval<DateTime>"))
    }

    // -------------------------------------------------------------------------
    // parseParameterValues tests
    // -------------------------------------------------------------------------

    @Test
    fun `parseParameterValues returns null for empty list`() {
        val result = parseParameterValues(r4Context, defaultSettings, emptyList())
        assertNull(result)
    }

    @Test
    fun `parseParameterValues evaluates integer literal`() {
        val params =
            listOf(
                ParameterRequest(parameterName = "MyInt", parameterType = "Integer", parameterValue = "42"),
            )
        val result = parseParameterValues(r4Context, defaultSettings, params)
        assertNotNull(result)
        assertEquals(42, result!!["MyInt"])
    }

    @Test
    fun `parseParameterValues evaluates string literal`() {
        val params =
            listOf(
                ParameterRequest(parameterName = "Plan", parameterType = "String", parameterValue = "'HMO'"),
            )
        val result = parseParameterValues(r4Context, defaultSettings, params)
        assertNotNull(result)
        assertEquals("HMO", result!!["Plan"])
    }

    @Test
    fun `parseParameterValues evaluates boolean literal`() {
        val params =
            listOf(
                ParameterRequest(parameterName = "Flag", parameterType = "Boolean", parameterValue = "true"),
            )
        val result = parseParameterValues(r4Context, defaultSettings, params)
        assertNotNull(result)
        assertEquals(true, result!!["Flag"])
    }

    @Test
    fun `parseParameterValues evaluates decimal literal`() {
        val params =
            listOf(
                ParameterRequest(parameterName = "Rate", parameterType = "Decimal", parameterValue = "3.14"),
            )
        val result = parseParameterValues(r4Context, defaultSettings, params)
        assertNotNull(result)
        assertEquals(java.math.BigDecimal("3.14"), result!!["Rate"])
    }

    @Test
    fun `parseParameterValues evaluates multiple parameters`() {
        val params =
            listOf(
                ParameterRequest(parameterName = "A", parameterType = "Integer", parameterValue = "1"),
                ParameterRequest(parameterName = "B", parameterType = "String", parameterValue = "'hello'"),
                ParameterRequest(parameterName = "C", parameterType = "Boolean", parameterValue = "false"),
            )
        val result = parseParameterValues(r4Context, defaultSettings, params)
        assertNotNull(result)
        assertEquals(1, result!!["A"])
        assertEquals("hello", result["B"])
        assertEquals(false, result["C"])
    }

    @Test
    fun `parseParameterValues returns null on invalid CQL expression`() {
        val params =
            listOf(
                ParameterRequest(parameterName = "Bad", parameterType = "Integer", parameterValue = "not valid cql !!"),
            )
        val result = parseParameterValues(r4Context, defaultSettings, params)
        assertNull(result)
    }

    // -------------------------------------------------------------------------
    // End-to-end evaluate tests with parameters
    // -------------------------------------------------------------------------

    /**
     * Verifies that bare date literals (@YYYY-MM-DD) in an Interval<DateTime> parameter override
     * are automatically coerced to DateTime literals before evaluation, preventing the
     * "Expected date from(DateTime), Found date from(Date)" runtime error.
     */
    @Test
    fun `evaluate coerces bare date literals to DateTime for Interval-DateTime parameter`() {
        val request =
            ExecuteCqlRequest(
                fhirVersion = "R4",
                rootDir = null,
                optionsPath = null,
                libraries =
                    listOf(
                        LibraryRequest(
                            libraryName = "WithDateTimeParam",
                            libraryUri = "file:///any/path",
                            libraryVersion = "1",
                            terminologyUri = null,
                            model = null,
                            context = null,
                            parameters =
                                listOf(
                                    // Bare date literals (no T suffix) — must not cause a type mismatch
                                    ParameterRequest(
                                        parameterName = "Measurement Period",
                                        parameterType = "Interval<DateTime>",
                                        parameterValue = "Interval[@2024-01-01, @2024-12-31)",
                                    ),
                                ),
                        ),
                    ),
            )

        val response = CqlEvaluator.evaluate(request, contentService, igContextManager, libraryResolutionManager)

        assertEquals(1, response.results.size)
        val libraryResult = response.results[0]
        // Must not produce an error expression
        assertTrue(
            libraryResult.expressions.none { it.name == "Error" },
            "Expected no errors but got: ${libraryResult.expressions.filter { it.name == "Error" }}",
        )
    }

    @Test
    fun `evaluate passes parameter override to library`() {
        val request =
            ExecuteCqlRequest(
                fhirVersion = "R4",
                rootDir = null,
                optionsPath = null,
                libraries =
                    listOf(
                        LibraryRequest(
                            libraryName = "WithParam",
                            libraryUri = "file:///any/path",
                            libraryVersion = "1",
                            terminologyUri = null,
                            model = null,
                            context = null,
                            parameters =
                                listOf(
                                    ParameterRequest(
                                        parameterName = "Rate",
                                        parameterType = "Decimal",
                                        parameterValue = "2.5",
                                    ),
                                ),
                        ),
                    ),
            )

        val response = CqlEvaluator.evaluate(request, contentService, igContextManager, libraryResolutionManager)

        assertEquals(1, response.results.size)
        val expressions = response.results[0].expressions.associateBy { it.name }
        // The parameter override (2.5) should be used instead of the default (1.0)
        assertTrue(
            expressions["Using Rate"]?.value?.contains("2.5") == true,
            "Expected 'Using Rate' to reflect overridden value 2.5, got: ${expressions["Using Rate"]?.value}",
        )
    }

    @Test
    fun `evaluate uses CQL default when no parameter override provided`() {
        val request =
            ExecuteCqlRequest(
                fhirVersion = "R4",
                rootDir = null,
                optionsPath = null,
                libraries =
                    listOf(
                        LibraryRequest(
                            libraryName = "WithParam",
                            libraryUri = "file:///any/path",
                            libraryVersion = "1",
                            terminologyUri = null,
                            model = null,
                            context = null,
                            parameters = emptyList(),
                        ),
                    ),
            )

        val response = CqlEvaluator.evaluate(request, contentService, igContextManager, libraryResolutionManager)

        assertEquals(1, response.results.size)
        val libraryResult = response.results[0]
        val expressions = libraryResult.expressions.associateBy { it.name }
        // No override — engine uses the CQL default (1.0)
        assertTrue(
            expressions["Using Rate"]?.value?.contains("1.0") == true ||
                expressions["Using Rate"]?.value?.contains("1") == true,
            "Expected 'Using Rate' to reflect default value 1.0, got: ${expressions["Using Rate"]?.value}",
        )
        // Both declared parameters had defaults and were not overridden → both in usedDefaultParameters
        val defaultParamNames = libraryResult.usedDefaultParameters.map { it.name }.toSet()
        assertTrue("Rate" in defaultParamNames, "Expected 'Rate' in usedDefaultParameters, got: $defaultParamNames")
        assertTrue(
            "Measurement Period" in defaultParamNames,
            "Expected 'Measurement Period' in usedDefaultParameters, got: $defaultParamNames",
        )
    }

    @Test
    fun `evaluate excludes overridden parameter from usedDefaultParameters`() {
        val request =
            ExecuteCqlRequest(
                fhirVersion = "R4",
                rootDir = null,
                optionsPath = null,
                libraries =
                    listOf(
                        LibraryRequest(
                            libraryName = "WithParam",
                            libraryUri = "file:///any/path",
                            libraryVersion = "1",
                            terminologyUri = null,
                            model = null,
                            context = null,
                            parameters =
                                listOf(
                                    ParameterRequest(
                                        parameterName = "Rate",
                                        parameterType = "Decimal",
                                        parameterValue = "2.5",
                                    ),
                                ),
                        ),
                    ),
            )

        val response = CqlEvaluator.evaluate(request, contentService, igContextManager, libraryResolutionManager)
        val libraryResult = response.results[0]
        val defaultParamNames = libraryResult.usedDefaultParameters.map { it.name }.toSet()

        // Rate was overridden → must NOT appear in usedDefaultParameters
        assertTrue("Rate" !in defaultParamNames, "Overridden 'Rate' must not be in usedDefaultParameters")
        // Measurement Period was not overridden → must appear
        assertTrue(
            "Measurement Period" in defaultParamNames,
            "Expected 'Measurement Period' in usedDefaultParameters, got: $defaultParamNames",
        )
    }
}
