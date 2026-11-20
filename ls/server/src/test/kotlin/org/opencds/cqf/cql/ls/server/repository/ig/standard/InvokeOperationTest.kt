package org.opencds.cqf.cql.ls.server.repository.ig.standard

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.repository.IRepository
import org.hl7.fhir.instance.model.api.IBaseParameters
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.instance.model.api.IIdType
import org.hl7.fhir.instance.model.api.IPrimitiveType
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.Library
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.fhir.utility.repository.IRepositoryOperationProvider
import java.nio.file.Path

/**
 * Tests for [IgStandardRepository.invoke] / invokeOperation, covering the
 * missing-provider, null-result and success paths.
 */
class InvokeOperationTest {
    @TempDir
    lateinit var tempDir: Path

    private class FakeProvider(
        private val result: IBaseResource?,
    ) : IRepositoryOperationProvider {
        var repository: IRepository? = null
        var id: IIdType? = null
        var resourceType: String? = null
        var name: String? = null
        var parameters: IBaseParameters? = null
        var calls = 0

        @Suppress("UNCHECKED_CAST")
        override fun <C : IPrimitiveType<String?>, R : IBaseResource> invokeOperation(
            repository: IRepository?,
            id: IIdType?,
            resourceType: String?,
            operationName: String?,
            parameters: IBaseParameters?,
        ): R? {
            calls++
            this.repository = repository
            this.id = id
            this.resourceType = resourceType
            this.name = operationName
            this.parameters = parameters
            return result as R?
        }
    }

    private fun repo(provider: IRepositoryOperationProvider?) =
        IgStandardRepository(
            FhirContext.forR4Cached(),
            tempDir,
            IgStandardConventions.STANDARD,
            IgStandardEncodingBehavior.DEFAULT,
            provider,
        )

    @Test
    fun invokeWithoutOperationProviderThrows() {
        val ex =
            assertThrows(IllegalStateException::class.java) {
                repo(null).invoke(Library::class.java, "evaluate", Parameters(), Parameters::class.java, null)
            }
        assertEquals("No operation provider found. Unable to invoke operations.", ex.message)
    }

    @Test
    fun invokeByIdWithoutOperationProviderThrows() {
        val ex =
            assertThrows(IllegalStateException::class.java) {
                repo(null).invoke(IdType("Library", "abc"), "evaluate", Parameters(), Parameters::class.java, null)
            }
        assertEquals("No operation provider found. Unable to invoke operations.", ex.message)
    }

    @Test
    fun invokeWhenProviderReturnsNullThrows() {
        val provider = FakeProvider(null)
        val ex =
            assertThrows(IllegalStateException::class.java) {
                repo(provider).invoke(Library::class.java, "evaluate", Parameters(), Parameters::class.java, null)
            }
        assertEquals("Operation \$evaluate returned no result.", ex.message)
        assertEquals(1, provider.calls)
    }

    @Test
    fun invokeByTypeReturnsProviderResult() {
        val expected = Patient()
        val params = Parameters()
        val provider = FakeProvider(expected)
        val repository = repo(provider)

        val actual = repository.invoke(Library::class.java, "evaluate", params, Patient::class.java, null)

        assertSame(expected, actual)
        assertSame(repository, provider.repository)
        assertNull(provider.id)
        assertEquals("Library", provider.resourceType)
        assertEquals("evaluate", provider.name)
        assertSame(params, provider.parameters)
    }

    @Test
    fun invokeByIdReturnsProviderResult() {
        val expected = Patient()
        val params = Parameters()
        val id = IdType("Library", "abc")
        val provider = FakeProvider(expected)

        val actual = repo(provider).invoke(id, "evaluate", params, Patient::class.java, null)

        assertSame(expected, actual)
        assertSame(id, provider.id)
        assertEquals("Library", provider.resourceType)
        assertEquals("evaluate", provider.name)
        assertSame(params, provider.parameters)
    }
}
