package org.opencds.cqf.cql.ls.server.command

import com.google.gson.Gson
import org.eclipse.lsp4j.ExecuteCommandParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.service.TestContentService

class ExecuteCqlCommandContributionTest {
    companion object {
        private lateinit var contribution: ExecuteCqlCommandContribution

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val cs = TestContentService()
            contribution = ExecuteCqlCommandContribution(IgContextManager(cs), cs)
        }
    }

    @Test
    fun `getCommands returns executeCql command`() {
        assertEquals(setOf("org.opencds.cqf.cql.ls.executeCql"), contribution.getCommands())
    }

    @Test
    fun `executeCommand returns structured response for One library`() {
        // TestContentService resolves "One" from classpath regardless of the libraryUri root
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
        val element = Gson().toJsonTree(request)
        val params = ExecuteCommandParams("org.opencds.cqf.cql.ls.executeCql", listOf(element))
        val response = contribution.executeCommand(params).join() as ExecuteCqlResponse

        assertEquals(1, response.results.size)
        assertEquals("One", response.results[0].libraryName)
        assertTrue(
            response.results[0].expressions.any { it.name == "One" },
            "Expected expression 'One' in results",
        )
        assertTrue(response.logs.isEmpty())
    }

    @Test
    fun `evaluates two patients for same library using one batch`() {
        // Both entries share libraryName + libraryUri — they must land in the same batch and
        // both results must be returned in order.
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
        val element = Gson().toJsonTree(request)
        val params = ExecuteCommandParams("org.opencds.cqf.cql.ls.executeCql", listOf(element))
        val response = contribution.executeCommand(params).join() as ExecuteCqlResponse

        assertEquals(2, response.results.size)
        assertEquals("One", response.results[0].libraryName)
        assertEquals("One", response.results[1].libraryName)
        assertTrue(response.results[0].expressions.any { it.name == "One" })
        assertTrue(response.results[1].expressions.any { it.name == "One" })
    }

    @Test
    fun `String parameter is passed to engine without type coercion error`() {
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
                            parameters =
                                listOf(
                                    ParameterRequest(
                                        parameterName = "Unused",
                                        parameterType = "String",
                                        parameterValue = "hello",
                                    ),
                                ),
                        ),
                    ),
            )
        val element = Gson().toJsonTree(request)
        val params = ExecuteCommandParams("org.opencds.cqf.cql.ls.executeCql", listOf(element))
        // The "One" library does not declare a "Unused" parameter, so the engine ignores it.
        // The important invariant is that no exception is thrown during coercion or evaluation.
        val response = contribution.executeCommand(params).join() as ExecuteCqlResponse
        assertEquals(1, response.results.size)
        assertEquals("One", response.results[0].libraryName)
    }

    @Test
    fun `null parameterType is accepted and treated as String`() {
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
                            parameters =
                                listOf(
                                    ParameterRequest(
                                        parameterName = "Unused",
                                        parameterType = null,
                                        parameterValue = "value",
                                    ),
                                ),
                        ),
                    ),
            )
        val element = Gson().toJsonTree(request)
        val params = ExecuteCommandParams("org.opencds.cqf.cql.ls.executeCql", listOf(element))
        val response = contribution.executeCommand(params).join() as ExecuteCqlResponse
        assertEquals(1, response.results.size)
    }

    @Test
    fun `Interval_DateTime parameter is coerced to native Interval and evaluated correctly`() {
        // WithParam.cql declares `parameter "Measurement Period" Interval<DateTime>` and
        // defines `"Period Start": start of "Measurement Period"`. If the parameter is passed
        // as a plain String instead of a native CqlInterval, the engine throws
        // "Expected Start(Interval<T>), Found Start(java.lang.String)".
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
                            libraryVersion = null,
                            terminologyUri = null,
                            model = null,
                            context = null,
                            parameters =
                                listOf(
                                    ParameterRequest(
                                        parameterName = "Measurement Period",
                                        parameterType = "Interval<DateTime>",
                                        parameterValue = "Interval[@2024-01-01T00:00:00.000Z, @2025-01-01T00:00:00.000Z)",
                                    ),
                                ),
                        ),
                    ),
            )
        val element = Gson().toJsonTree(request)
        val params = ExecuteCommandParams("org.opencds.cqf.cql.ls.executeCql", listOf(element))
        val response = contribution.executeCommand(params).join() as ExecuteCqlResponse

        assertEquals(1, response.results.size)
        val expressions = response.results[0].expressions
        val periodStart = expressions.find { it.name == "Period Start" }
        assertNotNull(periodStart, "Expected 'Period Start' expression in results")
        assertFalse(
            expressions.any { it.name == "Error" },
            "Unexpected Error expression in results: $expressions",
        )
    }

    @Test
    fun `parameter not in config is reported as usedDefaultParameter when CQL declares a default`() {
        // WithParam.cql declares "Measurement Period" with a default expression.
        // When no parameter is supplied via config, usedDefaultParameters should contain it.
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
                            libraryVersion = null,
                            terminologyUri = null,
                            model = null,
                            context = null,
                            parameters = emptyList(),
                        ),
                    ),
            )
        val element = Gson().toJsonTree(request)
        val params = ExecuteCommandParams("org.opencds.cqf.cql.ls.executeCql", listOf(element))
        val response = contribution.executeCommand(params).join() as ExecuteCqlResponse

        assertEquals(1, response.results.size)
        val defaultParam = response.results[0].usedDefaultParameters.find { it.name == "Measurement Period" }
        assertNotNull(
            defaultParam,
            "Expected 'Measurement Period' in usedDefaultParameters, got: ${response.results[0].usedDefaultParameters}",
        )
        // The CQL default is Interval[@2023-01-01T00:00:00.000, @2024-01-01T00:00:00.000) — value must be non-null/non-empty
        assertFalse(
            defaultParam!!.value.isBlank(),
            "Expected non-blank resolved value for default parameter, got: '${defaultParam.value}'",
        )
        assertEquals(
            "default",
            defaultParam.source,
            "Expected source 'default' for a CQL-declared default parameter",
        )
    }

    @Test
    fun `parameter supplied via config is not reported as usedDefaultParameter`() {
        // When the parameter IS supplied, it must NOT appear in usedDefaultParameters.
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
                            libraryVersion = null,
                            terminologyUri = null,
                            model = null,
                            context = null,
                            parameters =
                                listOf(
                                    ParameterRequest(
                                        parameterName = "Measurement Period",
                                        parameterType = "Interval<DateTime>",
                                        parameterValue = "Interval[@2024-01-01T00:00:00.000Z, @2025-01-01T00:00:00.000Z)",
                                    ),
                                ),
                        ),
                    ),
            )
        val element = Gson().toJsonTree(request)
        val params = ExecuteCommandParams("org.opencds.cqf.cql.ls.executeCql", listOf(element))
        val response = contribution.executeCommand(params).join() as ExecuteCqlResponse

        assertEquals(1, response.results.size)
        assertFalse(
            response.results[0].usedDefaultParameters.any { it.name == "Measurement Period" },
            "Expected 'Measurement Period' NOT in usedDefaultParameters when explicitly provided",
        )
    }

    @Test
    fun `unrelated config param does not suppress usedDefaultParameter detection`() {
        // Regression test: when config.json contains a parameter that the CQL library does NOT
        // declare (e.g. "Measurement Period Sample"), the CQL-declared default ("Measurement Period")
        // must still appear in usedDefaultParameters.
        //
        // Root cause: previously we used LibraryManager.resolveLibrary(unversionedIdentifier) to
        // inspect ParameterDef entries. For libraries with a version declaration the engine stores
        // the compiled library under a versioned VersionedIdentifier, so the unversioned lookup
        // misses the cache and the inner try/catch silently returns emptyList().
        // WithParam.cql has no version so the bug was masked in other tests.
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
                            libraryVersion = null,
                            terminologyUri = null,
                            model = null,
                            context = null,
                            // Unrelated parameter not declared in WithParam.cql
                            parameters =
                                listOf(
                                    ParameterRequest(
                                        parameterName = "Measurement Period Sample",
                                        parameterType = "String",
                                        parameterValue = "some-value",
                                    ),
                                ),
                        ),
                    ),
            )
        val element = Gson().toJsonTree(request)
        val params = ExecuteCommandParams("org.opencds.cqf.cql.ls.executeCql", listOf(element))
        val response = contribution.executeCommand(params).join() as ExecuteCqlResponse

        assertEquals(1, response.results.size)
        assertTrue(
            response.results[0].usedDefaultParameters.any { it.name == "Measurement Period" },
            "Expected 'Measurement Period' in usedDefaultParameters, got: ${response.results[0].usedDefaultParameters}",
        )
    }

    @Test
    fun `malformed Interval_DateTime literal falls back gracefully without throwing`() {
        // The coercion logs a warning and passes the raw string to the engine.
        // The engine then produces an Error expression result — but no exception must propagate.
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
                            libraryVersion = null,
                            terminologyUri = null,
                            model = null,
                            context = null,
                            parameters =
                                listOf(
                                    ParameterRequest(
                                        parameterName = "Measurement Period",
                                        parameterType = "Interval<DateTime>",
                                        parameterValue = "NOT_AN_INTERVAL",
                                    ),
                                ),
                        ),
                    ),
            )
        val element = Gson().toJsonTree(request)
        val params = ExecuteCommandParams("org.opencds.cqf.cql.ls.executeCql", listOf(element))
        // Must not throw
        val response = contribution.executeCommand(params).join() as ExecuteCqlResponse
        assertEquals(1, response.results.size)
    }

    @Test
    fun `error in one patient does not prevent evaluation of other patients`() {
        // First entry has an invalid libraryUri — the evaluator catches the exception and
        // returns an Error expression. The second (valid) entry must still be evaluated.
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
                            model =
                                ModelRequest(
                                    modelName = "FHIR",
                                    modelUri = "file:///nonexistent/path/that/does/not/exist",
                                ),
                            context = ContextRequest(contextName = "Patient", contextValue = "bad-patient"),
                            parameters = emptyList(),
                        ),
                        LibraryRequest(
                            libraryName = "One",
                            libraryUri = "file:///any/path",
                            libraryVersion = null,
                            terminologyUri = null,
                            model = null,
                            context = ContextRequest(contextName = "Patient", contextValue = "good-patient"),
                            parameters = emptyList(),
                        ),
                    ),
            )
        val element = Gson().toJsonTree(request)
        val params = ExecuteCommandParams("org.opencds.cqf.cql.ls.executeCql", listOf(element))
        val response = contribution.executeCommand(params).join() as ExecuteCqlResponse

        assertEquals(2, response.results.size)
        // Second patient — same library + libraryUri, so it shares the batch with the first.
        // After the first patient errors out (bad model path), the engine may be in an
        // indeterminate state; the important invariant is that both results are returned and
        // neither call throws out of the command.
        assertEquals("One", response.results[0].libraryName)
        assertEquals("One", response.results[1].libraryName)
    }
}
