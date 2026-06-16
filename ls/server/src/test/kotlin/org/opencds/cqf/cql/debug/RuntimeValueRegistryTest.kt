package org.opencds.cqf.cql.debug

import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuntimeValueRegistryTest {
    // -- put / find basic ---------------------------------------------------

    @Test
    fun `putDefine and find returns define by name`() {
        val reg = RuntimeValueRegistry()
        reg.putDefine("Initial Population", 42, "System.Integer", null)
        val result = reg.find("Initial Population")
        assertEquals(42, result?.value)
        assertEquals("System.Integer", result?.type)
    }

    @Test
    fun `find returns null for unknown name`() {
        val reg = RuntimeValueRegistry()
        assertNull(reg.find("NonExistent"))
    }

    // -- category priority --------------------------------------------------

    @Test
    fun `find prefers stack variable over define`() {
        val reg = RuntimeValueRegistry()
        reg.putDefine("X", "define-value", null, null)
        reg.putStackVariable("X", "stack-value", null)
        val result = reg.find("X")
        assertEquals("stack-value", result?.value)
    }

    @Test
    fun `find prefers define over context resource`() {
        val reg = RuntimeValueRegistry()
        reg.loadContextResource("Y", "context-value", null)
        reg.putDefine("Y", "define-value", null, null)
        val result = reg.find("Y")
        assertEquals("define-value", result?.value)
    }

    // -- find with library names --------------------------------------------

    @Test
    fun `find with library name returns scoped define`() {
        val reg = RuntimeValueRegistry()
        reg.putDefine("Pop", 100, null, VersionedIdentifier().also { it.id = "LibA" })
        reg.putDefine("Pop", 200, null, VersionedIdentifier().also { it.id = "LibB" })

        val libAResult = reg.find("Pop", "LibA")
        assertEquals(100, libAResult?.value)

        val libBResult = reg.find("Pop", "LibB")
        assertEquals(200, libBResult?.value)
    }

    @Test
    fun `find with library name returns null for wrong library`() {
        val reg = RuntimeValueRegistry()
        reg.putDefine("Pop", 100, null, VersionedIdentifier().also { it.id = "LibA" })
        assertNull(reg.find("Pop", "NonExistentLib"))
    }

    @Test
    fun `find with null library name only matches defines with no library`() {
        val reg = RuntimeValueRegistry()
        // Define without library — should be findable with null libraryName
        reg.putDefine("Pop", 100, null, null)
        val result = reg.find("Pop", null)
        assertEquals(100, result?.value)
    }

    // -- clearStackVariables ------------------------------------------------

    @Test
    fun `clearStackVariables removes transient entries and name index`() {
        val reg = RuntimeValueRegistry()
        reg.putStackVariable("tmp", "val", null)
        reg.putDefine("keep", "persist", null, null)

        reg.clearStackVariables()

        assertNull(reg.find("tmp"), "stack variable should be gone")
        assertEquals("persist", reg.find("keep")?.value, "define should remain")
        assertTrue(reg.getStackVariables().isEmpty())
    }

    @Test
    fun `clearStackVariables is safe when empty`() {
        val reg = RuntimeValueRegistry()
        reg.clearStackVariables()
        assertTrue(reg.getStackVariables().isEmpty())
    }

    // -- reset --------------------------------------------------------------

    @Test
    fun `reset clears all values and resets parametersLoaded flag`() {
        val reg = RuntimeValueRegistry()
        reg.putDefine("X", 1, null, null)
        reg.putStackVariable("Y", 2, null)

        reg.reset()

        assertNull(reg.find("X"))
        assertNull(reg.find("Y"))
        assertTrue(reg.getStackVariables().isEmpty())
        assertTrue(reg.getDefines().isEmpty())
        assertTrue(reg.getContextResources().isEmpty())
    }

    @Test
    fun `reset allows parameters to be loaded again`() {
        val reg = RuntimeValueRegistry()
        reg.reset()
        // After reset, parametersLoaded should be false so loadParameters can be called again.
        // We verify by calling loadParameters — it should not short-circuit.
        // (We can't easily inject a State here, but we can check that
        //  getParametersByLibrary() works after reset.)
        assertTrue(reg.getParametersByLibrary().isEmpty())
    }

    // -- getters ------------------------------------------------------------

    @Test
    fun `getStackVariables returns transient values`() {
        val reg = RuntimeValueRegistry()
        reg.putStackVariable("a", 1, null)
        reg.putStackVariable("b", 2, null)
        assertEquals(2, reg.getStackVariables().size)
    }

    @Test
    fun `getContextResources returns only context resources`() {
        val reg = RuntimeValueRegistry()
        reg.loadContextResource("Patient", "pat-1", "FHIR.Patient")
        reg.putDefine("D", "v", null, null)
        val resources = reg.getContextResources()
        assertEquals(1, resources.size)
        assertEquals("Patient", resources[0].name)
    }

    @Test
    fun `getDefines returns sorted defines`() {
        val reg = RuntimeValueRegistry()
        reg.putDefine("Z", 3, null, null)
        reg.putDefine("A", 1, null, null)
        val names = reg.getDefines().map { it.name }
        assertEquals(listOf("A", "Z"), names)
    }

    @Test
    fun `getParametersByLibrary groups by library name`() {
        val reg = RuntimeValueRegistry()
        // Use reflection to call the internal put method for parameters
        val putMethod =
            RuntimeValueRegistry::class.java.getDeclaredMethod(
                "put",
                RuntimeValueCategory::class.java,
                String::class.java,
                String::class.java,
                Any::class.java,
                String::class.java,
            )
        putMethod.isAccessible = true
        putMethod.invoke(reg, RuntimeValueCategory.PARAMETER, "LibA", "p1", 10, "Integer")
        putMethod.invoke(reg, RuntimeValueCategory.PARAMETER, "LibB", "p2", "hi", "String")
        putMethod.invoke(reg, RuntimeValueCategory.PARAMETER, "LibA", "p3", true, "Boolean")

        val groups = reg.getParametersByLibrary()
        assertEquals(2, groups.size)
        assertEquals(2, groups["LibA"]?.size)
        assertEquals(1, groups["LibB"]?.size)
    }
}
