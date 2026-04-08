package org.opencds.cqf.cql.ls.server.repository.ig.standard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IgStandardRepositoryCompartmentTest {
    // -----------------------------------------------------------------------
    // Default constructor — empty compartment
    // -----------------------------------------------------------------------

    @Test
    fun defaultConstructor_typeAndIdAreNull() {
        val c = IgStandardRepositoryCompartment()
        assertNull(c.type)
        assertNull(c.id)
    }

    @Test
    fun defaultConstructor_isEmpty() {
        val c = IgStandardRepositoryCompartment()
        assertTrue(c.isEmpty())
    }

    // -----------------------------------------------------------------------
    // String constructor — "ResourceType/Id" format
    // -----------------------------------------------------------------------

    @Test
    fun stringConstructor_parsesTypeAsLowercase() {
        val c = IgStandardRepositoryCompartment("Patient/123")
        assertEquals("patient", c.type)
    }

    @Test
    fun stringConstructor_parsesId() {
        val c = IgStandardRepositoryCompartment("Patient/123")
        assertEquals("123", c.id)
    }

    @Test
    fun stringConstructor_isNotEmpty() {
        val c = IgStandardRepositoryCompartment("Patient/123")
        assertFalse(c.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Type/Id constructor
    // -----------------------------------------------------------------------

    @Test
    fun typeIdConstructor_lowercasesType() {
        val c = IgStandardRepositoryCompartment("Encounter", "abc")
        assertEquals("encounter", c.type)
        assertEquals("abc", c.id)
    }

    @Test
    fun typeIdConstructor_alreadyLowercaseType_unchanged() {
        val c = IgStandardRepositoryCompartment("patient", "456")
        assertEquals("patient", c.type)
    }

    // -----------------------------------------------------------------------
    // isEmpty — only false when both type and id are non-null
    // -----------------------------------------------------------------------

    @Test
    fun isEmpty_withTypeAndId_returnsFalse() {
        assertFalse(IgStandardRepositoryCompartment("Patient", "123").isEmpty())
    }

    // -----------------------------------------------------------------------
    // equals / hashCode
    // -----------------------------------------------------------------------

    @Test
    fun equals_sameTypeAndId_returnsTrue() {
        val a = IgStandardRepositoryCompartment("Patient/123")
        val b = IgStandardRepositoryCompartment("Patient/123")
        assertEquals(a, b)
    }

    @Test
    fun equals_differentId_returnsFalse() {
        val a = IgStandardRepositoryCompartment("Patient/123")
        val b = IgStandardRepositoryCompartment("Patient/456")
        assertNotEquals(a, b)
    }

    @Test
    fun equals_differentType_returnsFalse() {
        val a = IgStandardRepositoryCompartment("Patient/123")
        val b = IgStandardRepositoryCompartment("Encounter/123")
        assertNotEquals(a, b)
    }

    @Test
    fun equals_nonCompartmentObject_returnsFalse() {
        val a = IgStandardRepositoryCompartment("Patient/123")
        assertNotEquals(a, "Patient/123")
    }

    @Test
    fun equals_twoEmptyCompartments_areEqual() {
        assertEquals(IgStandardRepositoryCompartment(), IgStandardRepositoryCompartment())
    }

    @Test
    fun hashCode_equalCompartments_haveSameHash() {
        val a = IgStandardRepositoryCompartment("Patient/123")
        val b = IgStandardRepositoryCompartment("Patient/123")
        assertEquals(a.hashCode(), b.hashCode())
    }

    // -----------------------------------------------------------------------
    // toString
    // -----------------------------------------------------------------------

    @Test
    fun toString_containsTypeAndId() {
        val c = IgStandardRepositoryCompartment("Patient/789")
        val s = c.toString()
        assertTrue(s.contains("patient"), "toString should contain the type")
        assertTrue(s.contains("789"), "toString should contain the id")
    }

    @Test
    fun toString_emptyCompartment_containsNulls() {
        val s = IgStandardRepositoryCompartment().toString()
        assertTrue(s.contains("null"))
    }

    // -----------------------------------------------------------------------
    // Validation — empty / null type or id throws
    // -----------------------------------------------------------------------

    @Test
    fun typeIdConstructor_emptyType_throwsIllegalArgumentException() {
        assertThrows<IllegalArgumentException> { IgStandardRepositoryCompartment("", "123") }
    }

    @Test
    fun typeIdConstructor_emptyId_throwsIllegalArgumentException() {
        assertThrows<IllegalArgumentException> { IgStandardRepositoryCompartment("Patient", "") }
    }
}
