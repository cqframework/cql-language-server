package org.opencds.cqf.cql.ls.server.command

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.repository.IRepository
import org.cqframework.fhir.npm.NpmProcessor
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r5.context.ILoggingService
import org.hl7.fhir.r5.model.DateTimeType
import org.hl7.fhir.r5.model.DateType
import org.hl7.fhir.r5.model.Quantity
import org.hl7.fhir.r5.model.TimeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService
import org.opencds.cqf.fhir.cql.CqlOptions
import org.opencds.cqf.fhir.cql.EvaluationSettings
import org.opencds.cqf.fhir.utility.repository.ProxyRepository
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

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

    private fun formatValue(value: Any?): String {
        val method = CqlEvaluator::class.java.getDeclaredMethod("formatValue", Any::class.java)
        method.isAccessible = true
        return method.invoke(CqlEvaluator, value) as String
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
    // formatValue tests
    // -------------------------------------------------------------------------

    @Test
    fun `formatValue returns null string for null input`() {
        assertEquals("null", formatValue(null))
    }

    @Test
    fun `formatValue returns toString for primitive types`() {
        assertEquals("42", formatValue(42))
        assertEquals("true", formatValue(true))
        assertEquals("3.14", formatValue(3.14))
    }

    @Test
    fun `formatValue formats non-empty Iterable as bracket-delimited list`() {
        assertEquals("[1, 2, 3]", formatValue(listOf(1, 2, 3)))
    }

    @Test
    fun `formatValue formats empty Iterable as empty brackets`() {
        assertEquals("[]", formatValue(emptyList<Any>()))
    }

    @Test
    fun `formatValue formats IBaseResource with id`() {
        val patient = Patient()
        patient.id = "abc"
        assertEquals("Patient(id=abc)", formatValue(patient))
    }

    @Test
    fun `formatValue formats IBaseResource without id`() {
        assertEquals("Patient", formatValue(Patient()))
    }

    @Test
    fun `formatValue formats IBaseDatatype via fhirType`() {
        // StringType is IBaseDatatype but NOT IBaseResource — verifies the branch ordering fix
        // (IBaseDatatype must be checked before IBase, otherwise IBase's branch matches first).
        val s = StringType("hello")
        assertEquals("string", formatValue(s))
    }

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
        assertEquals("42", result!!["MyInt"].toString())
    }

    @Test
    fun `parseParameterValues evaluates string literal`() {
        val params =
            listOf(
                ParameterRequest(parameterName = "Plan", parameterType = "String", parameterValue = "'HMO'"),
            )
        val result = parseParameterValues(r4Context, defaultSettings, params)
        assertNotNull(result)
        assertEquals("HMO", result!!["Plan"].toString())
    }

    @Test
    fun `parseParameterValues evaluates boolean literal`() {
        val params =
            listOf(
                ParameterRequest(parameterName = "Flag", parameterType = "Boolean", parameterValue = "true"),
            )
        val result = parseParameterValues(r4Context, defaultSettings, params)
        assertNotNull(result)
        assertEquals("true", result!!["Flag"].toString())
    }

    @Test
    fun `parseParameterValues evaluates decimal literal`() {
        val params =
            listOf(
                ParameterRequest(parameterName = "Rate", parameterType = "Decimal", parameterValue = "3.14"),
            )
        val result = parseParameterValues(r4Context, defaultSettings, params)
        assertNotNull(result)
        assertEquals("3.14", result!!["Rate"].toString())
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
        assertEquals("1", result!!["A"].toString())
        assertEquals("hello", result["B"].toString())
        assertEquals("false", result["C"].toString())
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
    fun `evaluate returns version info structure in response`() {
        val request =
            ExecuteCqlRequest(
                fhirVersion = "R4",
                rootDir = null,
                optionsPath = null,
                libraries =
                    listOf(
                        LibraryRequest(
                            libraryName = "One",
                            libraryUri = "file:///any/path",
                            libraryVersion = null,
                            terminologyUri = null,
                            model = null,
                            context = null,
                            parameters = emptyList(),
                        ),
                    ),
            )

        val response = CqlEvaluator.evaluate(request, contentService, igContextManager, libraryResolutionManager)

        assertNotNull(response.versions, "Expected versions in response")
        // Package.implementationVersion may be null when running outside a JAR (e.g. tests).
        // Verify the VersionInfo instance is present with the correct type.
        assertInstanceOf(VersionInfo::class.java, response.versions)
        // Ensure all four fields are present on the data class (even if values are null)
        val versionFields = response.versions!!::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("translator" in versionFields)
        assertTrue("engine" in versionFields)
        assertTrue("clinicalReasoning" in versionFields)
        assertTrue("languageServer" in versionFields)
    }

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
    fun `evaluate passes date parameter override to library`() {
        val request =
            ExecuteCqlRequest(
                fhirVersion = "R4",
                rootDir = null,
                optionsPath = null,
                libraries =
                    listOf(
                        LibraryRequest(
                            libraryName = "WithDateParam",
                            libraryUri = "file:///any/path",
                            libraryVersion = null,
                            terminologyUri = null,
                            model = null,
                            context = null,
                            parameters =
                                listOf(
                                    ParameterRequest(
                                        parameterName = "Date Range",
                                        parameterType = "Interval<Date>",
                                        parameterValue = "Interval[@2020-01-01, @2021-01-01)",
                                    ),
                                ),
                        ),
                    ),
            )

        val response = CqlEvaluator.evaluate(request, contentService, igContextManager, libraryResolutionManager)

        assertEquals(1, response.results.size)
        val expressions = response.results[0].expressions.associateBy { it.name }
        assertTrue(
            expressions["Range Start"]?.value?.contains("2020") == true,
            "Expected 'Range Start' to reflect overridden value, got: ${expressions["Range Start"]?.value}",
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

        assertTrue("Rate" !in defaultParamNames, "Overridden 'Rate' must not be in usedDefaultParameters")
        assertTrue(
            "Measurement Period" in defaultParamNames,
            "Expected 'Measurement Period' in usedDefaultParameters, got: $defaultParamNames",
        )
    }

    // -------------------------------------------------------------------------
    // Reflection helpers for untested private methods
    // -------------------------------------------------------------------------

    private fun coerceParameters(parameters: List<ParameterRequest>): MutableMap<String, Any?> {
        val method = CqlEvaluator::class.java.getDeclaredMethod("coerceParameters", List::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(CqlEvaluator, parameters) as MutableMap<String, Any?>
    }

    private fun parseCqlDateTimeValue(value: String): DateTimeType {
        val method = CqlEvaluator::class.java.getDeclaredMethod("parseCqlDateTimeValue", String::class.java)
        method.isAccessible = true
        return method.invoke(CqlEvaluator, value) as DateTimeType
    }

    private fun parseCqlDateValue(value: String): DateType {
        val method = CqlEvaluator::class.java.getDeclaredMethod("parseCqlDateValue", String::class.java)
        method.isAccessible = true
        return method.invoke(CqlEvaluator, value) as DateType
    }

    private fun parseCqlTimeValue(value: String): TimeType {
        val method = CqlEvaluator::class.java.getDeclaredMethod("parseCqlTimeValue", String::class.java)
        method.isAccessible = true
        return method.invoke(CqlEvaluator, value) as TimeType
    }

    private fun parseCqlQuantityValue(value: String): Quantity {
        val method = CqlEvaluator::class.java.getDeclaredMethod("parseCqlQuantityValue", String::class.java)
        method.isAccessible = true
        return method.invoke(CqlEvaluator, value) as Quantity
    }

    private fun createRepository(
        fhirContext: FhirContext,
        terminologyRepo: IRepository,
        modelPath: Path?,
    ): IRepository {
        val method = CqlEvaluator::class.java.getDeclaredMethod("createRepository", FhirContext::class.java, IRepository::class.java, Path::class.java)
        method.isAccessible = true
        return method.invoke(CqlEvaluator, fhirContext, terminologyRepo, modelPath) as IRepository
    }

    private fun buildCqlOptions(optionsPath: String?): CqlOptions {
        val method = CqlEvaluator::class.java.getDeclaredMethod("buildCqlOptions", String::class.java)
        method.isAccessible = true
        return method.invoke(CqlEvaluator, optionsPath) as CqlOptions
    }

    private fun buildEvaluationSettings(
        cqlOptions: CqlOptions,
        npmProcessor: NpmProcessor?,
    ): EvaluationSettings {
        val method =
            CqlEvaluator::class.java.getDeclaredMethod("buildEvaluationSettings", CqlOptions::class.java, NpmProcessor::class.java)
        method.isAccessible = true
        return method.invoke(CqlEvaluator, cqlOptions, npmProcessor) as EvaluationSettings
    }

    // -------------------------------------------------------------------------
    // coerceParameters tests
    // -------------------------------------------------------------------------

    @Test
    fun `coerceParameters coerces Integer`() {
        val result = coerceParameters(listOf(ParameterRequest("MyInt", "Integer", "42")))
        assertEquals(42, result["MyInt"])
    }

    @Test
    fun `coerceParameters returns raw string on invalid Integer`() {
        val result = coerceParameters(listOf(ParameterRequest("MyInt", "Integer", "not-a-number")))
        assertEquals("not-a-number", result["MyInt"])
    }

    @Test
    fun `coerceParameters coerces Decimal`() {
        val result = coerceParameters(listOf(ParameterRequest("MyDec", "Decimal", "3.14")))
        assertEquals(BigDecimal("3.14"), result["MyDec"])
    }

    @Test
    fun `coerceParameters returns raw string on invalid Decimal`() {
        val result = coerceParameters(listOf(ParameterRequest("MyDec", "Decimal", "bad")))
        assertEquals("bad", result["MyDec"])
    }

    @Test
    fun `coerceParameters coerces Boolean true`() {
        val result = coerceParameters(listOf(ParameterRequest("Flag", "Boolean", "true")))
        assertEquals(true, result["Flag"])
    }

    @Test
    fun `coerceParameters coerces Boolean false`() {
        val result = coerceParameters(listOf(ParameterRequest("Flag", "Boolean", "false")))
        assertEquals(false, result["Flag"])
    }

    @Test
    fun `coerceParameters returns raw string on invalid Boolean`() {
        val result = coerceParameters(listOf(ParameterRequest("Flag", "Boolean", "notbool")))
        assertEquals("notbool", result["Flag"])
    }

    @Test
    fun `coerceParameters coerces DateTime`() {
        val result = coerceParameters(listOf(ParameterRequest("Dt", "DateTime", "@2024-06-15T00:00:00.000Z")))
        assertInstanceOf(DateTimeType::class.java, result["Dt"])
    }

    @Test
    fun `coerceParameters returns raw string on invalid DateTime`() {
        val result = coerceParameters(listOf(ParameterRequest("Dt", "DateTime", "bad-date")))
        assertEquals("bad-date", result["Dt"])
    }

    @Test
    fun `coerceParameters coerces Date`() {
        val result = coerceParameters(listOf(ParameterRequest("Dt", "Date", "@2024-06-15")))
        assertInstanceOf(DateType::class.java, result["Dt"])
    }

    @Test
    fun `coerceParameters returns raw string on invalid Date`() {
        val result = coerceParameters(listOf(ParameterRequest("Dt", "Date", "bad-date")))
        assertEquals("bad-date", result["Dt"])
    }

    @Test
    fun `coerceParameters coerces Time`() {
        val result = coerceParameters(listOf(ParameterRequest("T", "Time", "@T12:00:00")))
        assertInstanceOf(TimeType::class.java, result["T"])
    }

    @Test
    fun `coerceParameters returns TimeType on unparseable Time string`() {
        val result = coerceParameters(listOf(ParameterRequest("T", "Time", "bad-time")))
        assertInstanceOf(TimeType::class.java, result["T"])
    }

    @Test
    fun `coerceParameters coerces Quantity with unit`() {
        val result = coerceParameters(listOf(ParameterRequest("Q", "Quantity", "5.4'mg'")))
        assertInstanceOf(Quantity::class.java, result["Q"])
        val q = result["Q"] as Quantity
        assertEquals(BigDecimal("5.4"), q.value)
        assertEquals("mg", q.unit)
    }

    @Test
    fun `coerceParameters coerces Quantity without unit`() {
        val result = coerceParameters(listOf(ParameterRequest("Q", "Quantity", "42")))
        assertInstanceOf(Quantity::class.java, result["Q"])
        val q = result["Q"] as Quantity
        assertEquals(BigDecimal("42"), q.value)
    }

    @Test
    fun `coerceParameters passes through Interval DateTime raw value`() {
        val result = coerceParameters(listOf(ParameterRequest("P", "Interval<DateTime>", "Interval[@2024-01-01, @2024-12-31)")))
        assertEquals("Interval[@2024-01-01, @2024-12-31)", result["P"])
    }

    @Test
    fun `coerceParameters passes through Interval Date raw value`() {
        val result = coerceParameters(listOf(ParameterRequest("P", "Interval<Date>", "Interval[@2024-01-01, @2024-12-31)")))
        assertEquals("Interval[@2024-01-01, @2024-12-31)", result["P"])
    }

    @Test
    fun `coerceParameters passes through String type`() {
        val result = coerceParameters(listOf(ParameterRequest("S", "String", "hello")))
        assertEquals("hello", result["S"])
    }

    @Test
    fun `coerceParameters passes through unknown type`() {
        val result = coerceParameters(listOf(ParameterRequest("S", "SomeCustomType", "hello")))
        assertEquals("hello", result["S"])
    }

    @Test
    fun `coerceParameters coerces multiple parameters of different types`() {
        val result =
            coerceParameters(
                listOf(
                    ParameterRequest("A", "Integer", "1"),
                    ParameterRequest("B", "String", "hi"),
                    ParameterRequest("C", "Boolean", "true"),
                    ParameterRequest("D", "Decimal", "2.5"),
                ),
            )
        assertEquals(1, result["A"])
        assertEquals("hi", result["B"])
        assertEquals(true, result["C"])
        assertEquals(BigDecimal("2.5"), result["D"])
    }

    // -------------------------------------------------------------------------
    // parseCqlDateTimeValue tests
    // -------------------------------------------------------------------------

    @Test
    fun `parseCqlDateTimeValue parses with at-prefix`() {
        val dt = parseCqlDateTimeValue("@2024-06-15T00:00:00.000Z")
        assertEquals("2024-06-15T00:00:00.000Z", dt.valueAsString)
    }

    @Test
    fun `parseCqlDateTimeValue parses without at-prefix`() {
        val dt = parseCqlDateTimeValue("2024-06-15T00:00:00.000Z")
        assertEquals("2024-06-15T00:00:00.000Z", dt.valueAsString)
    }

    // -------------------------------------------------------------------------
    // parseCqlDateValue tests
    // -------------------------------------------------------------------------

    @Test
    fun `parseCqlDateValue parses with at-prefix`() {
        val d = parseCqlDateValue("@2024-06-15")
        assertEquals("2024-06-15", d.valueAsString)
    }

    @Test
    fun `parseCqlDateValue parses without at-prefix`() {
        val d = parseCqlDateValue("2024-06-15")
        assertEquals("2024-06-15", d.valueAsString)
    }

    // -------------------------------------------------------------------------
    // parseCqlTimeValue tests
    // -------------------------------------------------------------------------

    @Test
    fun `parseCqlTimeValue parses with at-prefix`() {
        val t = parseCqlTimeValue("@T12:00:00")
        assertEquals("T12:00:00", t.valueAsString)
    }

    @Test
    fun `parseCqlTimeValue parses without at-prefix`() {
        val t = parseCqlTimeValue("T12:00:00")
        assertEquals("T12:00:00", t.valueAsString)
    }

    // -------------------------------------------------------------------------
    // parseCqlQuantityValue tests
    // -------------------------------------------------------------------------

    @Test
    fun `parseCqlQuantityValue parses with unit`() {
        val q = parseCqlQuantityValue("5.4'mg'")
        assertEquals(BigDecimal("5.4"), q.value)
        assertEquals("mg", q.unit)
    }

    @Test
    fun `parseCqlQuantityValue parses without unit`() {
        val q = parseCqlQuantityValue("42")
        assertEquals(BigDecimal("42"), q.value)
    }

    @Test
    fun `parseCqlQuantityValue handles invalid numeric part with unit`() {
        val q = parseCqlQuantityValue("not-a-number'mg'")
        assertEquals(BigDecimal.ZERO, q.value)
    }

    // -------------------------------------------------------------------------
    // createRepository tests
    // -------------------------------------------------------------------------

    @Test
    fun `createRepository with null modelPath returns ProxyRepository wrapping NoOpRepository`(
        @TempDir tempDir: Path,
    ) {
        val repo = createRepository(r4Context, createNoOpRepo(), null)
        assertInstanceOf(ProxyRepository::class.java, repo)
    }

    @Test
    fun `createRepository with modelPath returns ProxyRepository`(
        @TempDir tempDir: Path,
    ) {
        val dataDir = tempDir.resolve("data")
        Files.createDirectories(dataDir)
        val repo = createRepository(r4Context, createNoOpRepo(), dataDir)
        assertFalse(repo is NoOpRepository, "Model-path repository should not be NoOpRepository")
    }

    // -------------------------------------------------------------------------
    // buildCqlOptions tests
    // -------------------------------------------------------------------------

    @Test
    fun `buildCqlOptions with null path returns defaults`() {
        val opts = buildCqlOptions(null)
        assertNotNull(opts)
    }

    @Test
    fun `buildCqlOptions with options path loads from file`(
        @TempDir tempDir: Path,
    ) {
        val optsFile = tempDir.resolve("cql-options.json")
        optsFile.toFile().writeText("""{"cqlCompilerOptions": {"compatibilityLevel": "2.0"}}""")
        assertDoesNotThrow { buildCqlOptions(optsFile.toUri().toString()) }
    }

    // -------------------------------------------------------------------------
    // buildEvaluationSettings tests
    // -------------------------------------------------------------------------

    @Test
    fun `buildEvaluationSettings returns non-null EvaluationSettings`() {
        val result = buildEvaluationSettings(CqlOptions.defaultOptions(), null)
        assertNotNull(result)
    }

    @Test
    fun `buildEvaluationSettings preserves passed cqlOptions`() {
        val opts = CqlOptions.defaultOptions()
        val result = buildEvaluationSettings(opts, null)
        assertSame(opts, result.cqlOptions)
    }

    @Test
    fun `buildEvaluationSettings sets terminology settings`() {
        val result = buildEvaluationSettings(CqlOptions.defaultOptions(), null)
        assertNotNull(result.terminologySettings)
    }

    @Test
    fun `buildEvaluationSettings sets retrieve settings`() {
        val result = buildEvaluationSettings(CqlOptions.defaultOptions(), null)
        assertNotNull(result.retrieveSettings)
    }

    @Test
    fun `buildEvaluationSettings accepts null npmProcessor`() {
        val result = buildEvaluationSettings(CqlOptions.defaultOptions(), null)
        assertNull(result.npmProcessor)
    }

    @Test
    fun `buildEvaluationSettings accepts non-null npmProcessor`() {
        val npmProcessor = NpmProcessor(null)
        val result = buildEvaluationSettings(CqlOptions.defaultOptions(), npmProcessor)
        assertSame(npmProcessor, result.npmProcessor)
    }

    // -------------------------------------------------------------------------
    // CqlSourceStringProvider inner class tests
    // -------------------------------------------------------------------------

    @Test
    fun `CqlSourceStringProvider returns source when library id matches`() {
        val innerClass = CqlEvaluator::class.java.declaredClasses.first { it.simpleName == "CqlSourceStringProvider" }
        val ctor = innerClass.getDeclaredConstructor(String::class.java, String::class.java)
        ctor.isAccessible = true
        val provider = ctor.newInstance("testLib", "define X: 1")
        val getLibSourceMethod = innerClass.getDeclaredMethod("getLibrarySource", VersionedIdentifier::class.java)
        getLibSourceMethod.isAccessible = true
        val result = getLibSourceMethod.invoke(provider, VersionedIdentifier().withId("testLib"))
        assertNotNull(result)
    }

    @Test
    fun `CqlSourceStringProvider returns null when library id does not match`() {
        val innerClass = CqlEvaluator::class.java.declaredClasses.first { it.simpleName == "CqlSourceStringProvider" }
        val ctor = innerClass.getDeclaredConstructor(String::class.java, String::class.java)
        ctor.isAccessible = true
        val provider = ctor.newInstance("testLib", "define X: 1")
        val getLibSourceMethod = innerClass.getDeclaredMethod("getLibrarySource", VersionedIdentifier::class.java)
        getLibSourceMethod.isAccessible = true
        val result = getLibSourceMethod.invoke(provider, VersionedIdentifier().withId("otherLib"))
        assertNull(result)
    }

    // -------------------------------------------------------------------------
    // Logger inner class tests
    // -------------------------------------------------------------------------

    @Test
    fun `Logger logMessage does not throw`() {
        val innerClass = CqlEvaluator::class.java.declaredClasses.first { it.simpleName == "Logger" }
        val ctor = innerClass.getDeclaredConstructor()
        ctor.isAccessible = true
        val logger = ctor.newInstance()
        val logMessageMethod = innerClass.getDeclaredMethod("logMessage", String::class.java)
        logMessageMethod.isAccessible = true
        assertDoesNotThrow { logMessageMethod.invoke(logger, "test message") }
    }

    @Test
    fun `Logger logDebugMessage does not throw`() {
        val innerClass = CqlEvaluator::class.java.declaredClasses.first { it.simpleName == "Logger" }
        val ctor = innerClass.getDeclaredConstructor()
        ctor.isAccessible = true
        val logger = ctor.newInstance()
        val logDebugMethod = innerClass.getDeclaredMethod("logDebugMessage", ILoggingService.LogCategory::class.java, String::class.java)
        logDebugMethod.isAccessible = true
        assertDoesNotThrow { logDebugMethod.invoke(logger, ILoggingService.LogCategory.INIT, "debug msg") }
    }

    @Test
    fun `Logger isDebugLogging returns Boolean`() {
        val innerClass = CqlEvaluator::class.java.declaredClasses.first { it.simpleName == "Logger" }
        val ctor = innerClass.getDeclaredConstructor()
        ctor.isAccessible = true
        val logger = ctor.newInstance()
        val isDebugMethod = innerClass.getDeclaredMethod("isDebugLogging")
        isDebugMethod.isAccessible = true
        val result = isDebugMethod.invoke(logger)
        assertInstanceOf(java.lang.Boolean::class.java, result)
    }

    // -------------------------------------------------------------------------
    // evaluate — no results expression test
    // -------------------------------------------------------------------------

    @Test
    fun `evaluate returns Error expression when library evaluation fails`() {
        val request =
            ExecuteCqlRequest(
                fhirVersion = "R4",
                rootDir = null,
                optionsPath = null,
                libraries =
                    listOf(
                        LibraryRequest(
                            libraryName = "NonExistentLib",
                            libraryUri = "file:///nonexistent/path",
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
        val errorExpr = response.results[0].expressions.find { it.name == "Error" }
        assertNotNull(errorExpr, "Expected an Error expression for a failed library evaluation")
    }

    // -------------------------------------------------------------------------
    // evaluate — multiple libraries batching
    // -------------------------------------------------------------------------

    @Test
    fun `evaluate processes multiple libraries in same request`() {
        val request =
            ExecuteCqlRequest(
                fhirVersion = "R4",
                rootDir = null,
                optionsPath = null,
                libraries =
                    listOf(
                        LibraryRequest("WithParam", "file:///any/path", "1", null, null, null, emptyList()),
                        LibraryRequest("WithDateTimeParam", "file:///any/path", "1", null, null, null, emptyList()),
                    ),
            )
        val response = CqlEvaluator.evaluate(request, contentService, igContextManager, libraryResolutionManager)
        assertEquals(2, response.results.size)
        assertTrue(response.results.all { it.expressions.none { e -> e.name == "Error" } })
    }

    // -------------------------------------------------------------------------
    // evaluate — null rootDir path
    // -------------------------------------------------------------------------

    @Test
    fun `evaluate with null rootDir does not throw`() {
        val request =
            ExecuteCqlRequest(
                fhirVersion = "R4",
                rootDir = null,
                optionsPath = null,
                libraries =
                    listOf(
                        LibraryRequest("WithParam", "file:///any/path", "1", null, null, null, emptyList()),
                    ),
            )
        assertDoesNotThrow {
            CqlEvaluator.evaluate(request, contentService, igContextManager, libraryResolutionManager)
        }
    }

    private fun createNoOpRepo(): IRepository = NoOpRepository(r4Context)
}
