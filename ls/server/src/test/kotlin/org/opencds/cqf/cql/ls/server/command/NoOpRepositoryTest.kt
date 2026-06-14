package org.opencds.cqf.cql.ls.server.command

import ca.uhn.fhir.context.FhirContext
import com.google.common.collect.ArrayListMultimap
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NoOpRepositoryTest {
    private val fhirContext = FhirContext.forR4Cached()
    private val repo = NoOpRepository(fhirContext)

    @Test
    fun `read throws UnsupportedOperationException`() {
        assertThrows(UnsupportedOperationException::class.java) {
            repo.read(Patient::class.java, Patient().apply { id = "1" }.idElement, emptyMap())
        }
    }

    @Test
    fun `create throws UnsupportedOperationException`() {
        assertThrows(UnsupportedOperationException::class.java) {
            repo.create(Patient(), emptyMap())
        }
    }

    @Test
    fun `update throws UnsupportedOperationException`() {
        assertThrows(UnsupportedOperationException::class.java) {
            repo.update(Patient(), emptyMap())
        }
    }

    @Test
    fun `delete throws UnsupportedOperationException`() {
        assertThrows(UnsupportedOperationException::class.java) {
            repo.delete(Patient::class.java, Patient().apply { id = "1" }.idElement, emptyMap())
        }
    }

    @Test
    fun `search returns empty bundle`() {
        val result =
            repo.search(
                org.hl7.fhir.r4.model.Bundle::class.java,
                Patient::class.java,
                ArrayListMultimap.create(),
                emptyMap(),
            )
        assertNotNull(result)
        assertEquals(0, result.entry.size)
    }

    @Test
    fun `invoke throws UnsupportedOperationException for class overload`() {
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
    fun `invoke throws UnsupportedOperationException for id overload`() {
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

    @Test
    fun `fhirContext returns configured context`() {
        assertEquals(fhirContext, repo.fhirContext())
    }
}
