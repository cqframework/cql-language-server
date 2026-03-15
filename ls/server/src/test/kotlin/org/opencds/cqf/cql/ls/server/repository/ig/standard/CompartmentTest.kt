package org.opencds.cqf.cql.ls.server.repository.ig.standard

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.repository.IRepository
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Library
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.ValueSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.fhir.test.Resources
import org.hl7.fhir.instance.model.api.IIdType
import org.opencds.cqf.fhir.utility.Ids
import org.opencds.cqf.fhir.utility.search.Searches

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CompartmentTest {

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
            Resources.copyFromJar("/sampleIgs/ig/standard/compartment", tempDir!!)
            repository = IgStandardRepository(FhirContext.forR4Cached(), tempDir!!)
        }
    }

    @Test
    fun readLibrary() {
        val id: IIdType = Ids.newId(Library::class.java, "123")
        val lib = repository.read(Library::class.java, id)
        assertNotNull(lib)
        assertEquals(id.idPart, lib.idElement.idPart)
    }

    @Test
    fun readLibraryNotExists() {
        val id: IIdType = Ids.newId(Library::class.java, "DoesNotExist")
        assertThrows(ResourceNotFoundException::class.java) { repository.read(Library::class.java, id) }
    }

    @Test
    fun searchLibrary() {
        val libs = repository.search(Bundle::class.java, Library::class.java, Searches.ALL)

        assertNotNull(libs)
        assertEquals(2, libs.entry.size)
    }

    @Test
    fun searchLibraryNotExists() {
        val libs = repository.search(Bundle::class.java, Library::class.java, Searches.byUrl("not-exists"))
        assertNotNull(libs)
        assertEquals(0, libs.entry.size)
    }

    @Test
    fun readPatientNoCompartment() {
        val id: IIdType = Ids.newId(Patient::class.java, "123")
        assertThrows(ResourceNotFoundException::class.java) { repository.read(Patient::class.java, id) }
    }

    @Test
    fun readPatient() {
        val id: IIdType = Ids.newId(Patient::class.java, "123")
        val p = repository.read(Patient::class.java, id, mapOf(IgStandardRepository.FHIR_COMPARTMENT_HEADER to "Patient/123"))

        assertNotNull(p)
        assertEquals(id.idPart, p.idElement.idPart)
    }

    @Test
    fun searchEncounterNoCompartment() {
        val encounters = repository.search(Bundle::class.java, Encounter::class.java, Searches.ALL)
        assertNotNull(encounters)
        assertEquals(0, encounters.entry.size)
    }

    @Test
    fun searchEncounter() {
        val encounters = repository.search(
            Bundle::class.java,
            Encounter::class.java,
            Searches.ALL,
            mapOf(IgStandardRepository.FHIR_COMPARTMENT_HEADER to "Patient/123")
        )
        assertNotNull(encounters)
        assertEquals(1, encounters.entry.size)
    }

    @Test
    fun readValueSetNoCompartment() {
        val id: IIdType = Ids.newId(ValueSet::class.java, "456")
        val vs = repository.read(ValueSet::class.java, id)

        assertNotNull(vs)
        assertEquals(vs.idElement.idPart, vs.idElement.idPart)
    }

    // Terminology resources are not in compartments
    @Test
    fun readValueSet() {
        val id: IIdType = Ids.newId(ValueSet::class.java, "456")
        val vs = repository.read(
            ValueSet::class.java, id, mapOf(IgStandardRepository.FHIR_COMPARTMENT_HEADER to "Patient/123")
        )

        assertNotNull(vs)
        assertEquals(vs.idElement.idPart, vs.idElement.idPart)
    }

    @Test
    fun searchValueSet() {
        val sets = repository.search(Bundle::class.java, ValueSet::class.java, Searches.byUrl("example.com/ValueSet/456"))
        assertNotNull(sets)
        assertEquals(1, sets.entry.size)
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
    fun createAndDeletePatient() {
        val p = Patient()
        p.id = "new-patient"
        val header = mapOf(IgStandardRepository.FHIR_COMPARTMENT_HEADER to "Patient/new-patient")
        val o = repository.create(p, header)
        val created = repository.read(Patient::class.java, o.id!!, header)
        assertNotNull(created)

        val loc = tempDir!!.resolve("tests/patient/new-patient/patient/new-patient.json")
        assertTrue(Files.exists(loc))

        repository.delete(Patient::class.java, created.idElement, header)
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
    fun updatePatient() {
        val id: IIdType = Ids.newId(Patient::class.java, "123")
        val p = repository.read(Patient::class.java, id, mapOf(IgStandardRepository.FHIR_COMPARTMENT_HEADER to "Patient/123"))
        assertFalse(p.hasActive())

        p.active = true
        repository.update(p)

        val updated = repository.read(Patient::class.java, id, mapOf(IgStandardRepository.FHIR_COMPARTMENT_HEADER to "Patient/123"))
        assertTrue(updated.hasActive())
        assertTrue(updated.active)
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

    @Test
    fun searchById() {
        val bundle = repository.search(Bundle::class.java, Library::class.java, Searches.byId("123"))
        assertNotNull(bundle)
        assertEquals(1, bundle.entry.size)
    }

    @Test
    fun searchByIdNotFound() {
        val bundle = repository.search(Bundle::class.java, Library::class.java, Searches.byId("DoesNotExist"))
        assertNotNull(bundle)
        assertEquals(0, bundle.entry.size)
    }

    @Test
    @Order(1) // Do this test first because it puts the filesystem (temporarily) in an invalid state
    fun resourceMissingWhenCacheCleared() {
        val id = org.hl7.fhir.r4.model.IdType("Library", "ToDelete")
        var lib = Library().setIdElement(id)
        val path = tempDir!!.resolve("resources/library/ToDelete.json")

        repository.create(lib)
        assertTrue(path.toFile().exists())

        // Read back, should exist
        lib = repository.read(Library::class.java, id)
        assertNotNull(lib)

        // Overwrite the file on disk.
        Files.writeString(path, "")

        // Read from cache, repo doesn't know the content is gone.
        lib = repository.read(Library::class.java, id)
        assertNotNull(lib)
        assertEquals("ToDelete", lib.idElement.idPart)

        (repository as IgStandardRepository).clearCache()

        // Try to read again, should be gone because it's not in the cache and the content is gone.
        assertThrows(ResourceNotFoundException::class.java) { repository.read(Library::class.java, id) }

        // Clean up so that we don't affect other tests
        path.toFile().delete()
    }
}
