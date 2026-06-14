package org.opencds.cqf.cql.ls.server.command

import ca.uhn.fhir.context.FhirContext
import com.google.common.collect.ArrayListMultimap
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opencds.cqf.fhir.utility.repository.ProxyRepository

class DelegatingRepositoryTest {
    private val fhirContext = FhirContext.forR4Cached()

    @Test
    fun `fhirContext delegates to current`() {
        val repo = DelegatingRepository(NoOpRepository(fhirContext))
        assertEquals(fhirContext, repo.fhirContext())
    }

    @Test
    fun `read delegates to current and throws when current is NoOpRepository`() {
        val repo = DelegatingRepository(NoOpRepository(fhirContext))
        assertThrows(UnsupportedOperationException::class.java) {
            repo.read(Patient::class.java, Patient().apply { id = "1" }.idElement, emptyMap())
        }
    }

    @Test
    fun `search delegates to current and returns empty bundle from NoOpRepository`() {
        val repo = DelegatingRepository(NoOpRepository(fhirContext))
        val result = repo.search(Bundle::class.java, Patient::class.java, ArrayListMultimap.create(), emptyMap())
        // NoOpRepository returns an empty bundle
        assertTrue(result.entry.isEmpty())
    }

    @Test
    fun `swapping current changes fhirContext`() {
        val ctx1 = FhirContext.forR4Cached()
        val ctx2 = FhirContext.forR5Cached()
        val repo = DelegatingRepository(NoOpRepository(ctx1))
        assertEquals(ctx1, repo.fhirContext())

        repo.current = NoOpRepository(ctx2)
        assertEquals(ctx2, repo.fhirContext())
    }

    @Test
    fun `swapping current changes read behavior`() {
        val repo = DelegatingRepository(NoOpRepository(fhirContext))
        // First repo throws on read
        assertThrows(UnsupportedOperationException::class.java) {
            repo.read(Patient::class.java, Patient().apply { id = "1" }.idElement, emptyMap())
        }

        // Swap to a second NoOpRepository — still throws, but via the new delegate
        val noOp2 = NoOpRepository(fhirContext)
        repo.current = noOp2
        assertThrows(UnsupportedOperationException::class.java) {
            repo.read(Patient::class.java, Patient().apply { id = "1" }.idElement, emptyMap())
        }
    }

    @Test
    fun `ProxyRepository backed by DelegatingRepository reflects current swap`() {
        val delegating = DelegatingRepository(NoOpRepository(fhirContext))
        val proxy = ProxyRepository(delegating, delegating, delegating)

        // Search via the proxy should pass through the delegate to NoOp (empty bundle)
        val result = proxy.search(Bundle::class.java, Patient::class.java, ArrayListMultimap.create(), emptyMap())
        assertTrue(result.entry.isEmpty())

        // Swap to another NoOp — proxy still delegates correctly
        delegating.current = NoOpRepository(fhirContext)
        val result2 = proxy.search(Bundle::class.java, Patient::class.java, ArrayListMultimap.create(), emptyMap())
        assertTrue(result2.entry.isEmpty())
    }

    @Test
    fun `create delegates to current and throws`() {
        val repo = DelegatingRepository(NoOpRepository(fhirContext))
        assertThrows(UnsupportedOperationException::class.java) {
            repo.create(Patient(), emptyMap())
        }
    }

    @Test
    fun `update delegates to current and throws`() {
        val repo = DelegatingRepository(NoOpRepository(fhirContext))
        assertThrows(UnsupportedOperationException::class.java) {
            repo.update(Patient(), emptyMap())
        }
    }

    @Test
    fun `delete delegates to current and throws`() {
        val repo = DelegatingRepository(NoOpRepository(fhirContext))
        assertThrows(UnsupportedOperationException::class.java) {
            repo.delete(Patient::class.java, Patient().apply { id = "1" }.idElement, emptyMap())
        }
    }

    @Test
    fun `invoke class overload delegates to current and throws`() {
        val repo = DelegatingRepository(NoOpRepository(fhirContext))
        assertThrows(UnsupportedOperationException::class.java) {
            repo.invoke(
                Patient::class.java,
                "Operation",
                org.hl7.fhir.r4.model.Parameters(),
                Patient::class.java,
                emptyMap(),
            )
        }
    }

    @Test
    fun `invoke id overload delegates to current and throws`() {
        val repo = DelegatingRepository(NoOpRepository(fhirContext))
        assertThrows(UnsupportedOperationException::class.java) {
            repo.invoke(
                Patient().apply { id = "1" }.idElement,
                "Operation",
                org.hl7.fhir.r4.model.Parameters(),
                Patient::class.java,
                emptyMap(),
            )
        }
    }
}
