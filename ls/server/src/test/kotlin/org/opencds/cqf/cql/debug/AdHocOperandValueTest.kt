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
import org.opencds.cqf.fhir.utility.model.FhirModelResolverCache
import java.time.ZoneOffset
import org.opencds.cqf.cql.engine.runtime.Date as CqlDate
import org.opencds.cqf.cql.engine.runtime.DateTime as CqlDateTime
import org.opencds.cqf.cql.engine.runtime.Time as CqlTime

class AdHocOperandValueTest {
    private val resolver: FhirModelResolver<*, *, *, *, *, *, *, *> =
        FhirModelResolverCache.resolverForVersion(FhirVersionEnum.R4)

    @Test
    fun `raw FHIR Observation converts to a ClassInstance Value`() {
        val observation = Observation().apply { id = "obs-1" }
        val result = convertToAdHocOperandValue("PalliativeAssessment", observation, resolver)
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
    fun `raw Kotlin Double is rejected with its boxed type name`() {
        // No engine/CQL path produces a raw JVM Double (Decimal coerces to BigDecimal), so this
        // allow-list boundary is only constructible as a direct converter call.
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                convertToAdHocOperandValue("D", 1.5, resolver)
            }
        assertTrue(ex.message!!.contains("'D'"), ex.message)
        assertTrue(ex.message!!.contains("Double"), ex.message)
    }

    @Test
    fun `raw Kotlin Float is rejected with its boxed type name`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                convertToAdHocOperandValue(
                    "F",
                    1.5f,
                    resolver,
                )
            }
        assertTrue(ex.message!!.contains("'F'"), ex.message)
        assertTrue(ex.message!!.contains("Float"), ex.message)
    }

    @Test
    fun `raw Kotlin Char is rejected with its boxed type name`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                convertToAdHocOperandValue("C", 'x', resolver)
            }
        assertTrue(ex.message!!.contains("'C'"), ex.message)
        assertTrue(ex.message!!.contains("Character"), ex.message)
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
    fun `temporal Values pass through unchanged`() {
        // Date/DateTime/Time extend BaseTemporal (a SimpleValue/Value), so the registry now keeps
        // them as real engine Values and the converter must bind them unchanged — never re-wrap
        // their stringified form.
        val date = CqlDate("2024-01-15")
        val dateTime = CqlDateTime("2024-01-15T10:30:00.000", ZoneOffset.UTC)
        val time = CqlTime("10:30:00")
        assertSame(date, convertToAdHocOperandValue("D", date, resolver))
        assertSame(dateTime, convertToAdHocOperandValue("DT", dateTime, resolver))
        assertSame(time, convertToAdHocOperandValue("T", time, resolver))
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
