package org.opencds.cqf.cql.debug

import ca.uhn.fhir.context.FhirVersionEnum
import org.hl7.fhir.r4.model.Observation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.engine.fhir.model.FhirModelResolver
import org.opencds.cqf.cql.engine.runtime.Interval
import org.opencds.cqf.cql.engine.runtime.Value
import org.opencds.cqf.fhir.utility.model.FhirModelResolverCache

class AdHocOperandValueTest {
    private val resolver: FhirModelResolver<*, *, *, *, *, *, *, *> =
        FhirModelResolverCache.resolverForVersion(FhirVersionEnum.R4)

    @Test
    fun `raw FHIR Observation converts to a ClassInstance Value`() {
        val observation = Observation().apply { id = "obs-1" }
        val result = convertToAdHocOperandValue("PalliativeAssessment", observation, resolver)
        assertTrue(result is Value)
        assertTrue(result is org.opencds.cqf.cql.engine.runtime.ClassInstance)
    }

    @Test
    fun `raw Kotlin String reverse-wraps to engine String`() {
        val result = convertToAdHocOperandValue("S", "hello", resolver)
        assertEquals(org.opencds.cqf.cql.engine.runtime.String("hello"), result)
    }

    @Test
    fun `raw Kotlin Boolean reverse-wraps to engine Boolean`() {
        val result = convertToAdHocOperandValue("B", true, resolver)
        assertEquals(org.opencds.cqf.cql.engine.runtime.Boolean(true), result)
    }

    @Test
    fun `raw Kotlin Int reverse-wraps to engine Integer`() {
        val result = convertToAdHocOperandValue("I", 5, resolver)
        assertEquals(org.opencds.cqf.cql.engine.runtime.Integer(5), result)
    }

    @Test
    fun `raw Kotlin Long reverse-wraps to engine Long`() {
        val result = convertToAdHocOperandValue("L", 5L, resolver)
        assertEquals(org.opencds.cqf.cql.engine.runtime.Long(5L), result)
    }

    @Test
    fun `already-a-Value passes through unchanged`() {
        val interval =
            Interval(
                org.opencds.cqf.cql.engine.runtime.Integer(1),
                true,
                org.opencds.cqf.cql.engine.runtime.Integer(5),
                true,
            )
        assertSame(interval, convertToAdHocOperandValue("Ivl", interval, resolver))
    }

    @Test
    fun `unconvertible value throws with alias name and type`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                convertToAdHocOperandValue("Bad", SomeUnconvertibleType(), resolver)
            }
        assertTrue(ex.message!!.contains("Bad"))
        assertTrue(ex.message!!.contains("SomeUnconvertibleType"))
    }

    private class SomeUnconvertibleType
}
