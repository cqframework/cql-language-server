package org.opencds.cqf.cql.ls.server.repository.ig.standard

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.repository.IRepository
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.CodeSystem
import org.hl7.fhir.r4.model.Condition
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Library
import org.hl7.fhir.r4.model.Measure
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.ValueSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.fhir.test.Resources
import org.hl7.fhir.instance.model.api.IIdType
import org.opencds.cqf.fhir.utility.Ids
import org.opencds.cqf.fhir.utility.search.Searches

/**
 * This set of tests ensures that we can create new directories as needed if
 * they don't exist ahead of time
 */
class NoTestDataTest {

    companion object {
        private lateinit var repository: IRepository

        @TempDir
        @JvmField
        var tempDir: Path? = null

        @BeforeAll
        @JvmStatic
        fun setup() {
            // This copies the sample IG to a temporary directory so that
            // we can test against an actual filesystem
            Resources.copyFromJar("/sampleIgs/ig/standard/noTestData", tempDir!!)
            repository = IgStandardRepository(FhirContext.forR4Cached(), tempDir!!)
        }
    }

    @Test
    fun createAndDeleteLibrary() {
        val lib = Library()
        lib.id = "new-library"
        val o = repository.create(lib)
        val created = repository.read(Library::class.java, o.id!!)
        assertNotNull(created)

        val loc = tempDir!!.resolve("resources/library/new-library.json")
        assertTrue(Files.exists(loc))

        repository.delete(Library::class.java, created.idElement)
        assertFalse(Files.exists(loc))
    }

    @Test
    fun createAndDeleteMeasure() {
        val measure = Measure()
        measure.id = "new-measure"
        val o = repository.create(measure)
        val created = repository.read(Measure::class.java, o.id!!)
        assertNotNull(created)

        val loc = tempDir!!.resolve("resources/measure/new-measure.json")
        assertTrue(Files.exists(loc))

        repository.delete(Measure::class.java, created.idElement)
        assertFalse(Files.exists(loc))
    }

    @Test
    fun createAndDeletePatient() {
        val p = Patient()
        p.id = "new-patient"
        val o = repository.create(p)
        val created = repository.read(Patient::class.java, o.id!!)
        assertNotNull(created)

        val loc = tempDir!!.resolve("tests/patient/new-patient.json")
        assertTrue(Files.exists(loc))

        repository.delete(Patient::class.java, created.idElement)
        assertFalse(Files.exists(loc))
    }

    @Test
    fun createAndDeleteCondition() {
        val p = Condition()
        p.id = "new-condition"
        val o = repository.create(p)
        val created = repository.read(Condition::class.java, o.id!!)
        assertNotNull(created)

        val loc = tempDir!!.resolve("tests/condition/new-condition.json")
        assertTrue(Files.exists(loc))

        repository.delete(Condition::class.java, created.idElement)
        assertFalse(Files.exists(loc))
    }

    @Test
    fun createAndDeleteValueSet() {
        val v = ValueSet()
        v.id = "new-valueset"
        val o = repository.create(v)
        val created = repository.read(ValueSet::class.java, o.id!!)
        assertNotNull(created)

        val loc = tempDir!!.resolve("vocabulary/valueset/new-valueset.json")
        assertTrue(Files.exists(loc))

        repository.delete(ValueSet::class.java, created.idElement)
        assertFalse(Files.exists(loc))
    }

    @Test
    fun createAndDeleteCodeSystem() {
        val c = CodeSystem()
        c.id = "new-codesystem"
        val o = repository.create(c)
        val created = repository.read(CodeSystem::class.java, o.id!!)
        assertNotNull(created)

        val loc = tempDir!!.resolve("vocabulary/codesystem/new-codesystem.json")
        assertTrue(Files.exists(loc))

        repository.delete(CodeSystem::class.java, created.idElement)
        assertFalse(Files.exists(loc))
    }

    @Test
    fun deleteNonExistentPatient() {
        val id: IIdType = Ids.newId(Patient::class.java, "DoesNotExist")
        assertThrows(ResourceNotFoundException::class.java) { repository.delete(Patient::class.java, id) }
    }

    @Test
    fun searchNonExistentType() {
        val results = repository.search(Bundle::class.java, Encounter::class.java, Searches.ALL)
        assertNotNull(results)
        assertEquals(0, results.entry.size)
    }
}
