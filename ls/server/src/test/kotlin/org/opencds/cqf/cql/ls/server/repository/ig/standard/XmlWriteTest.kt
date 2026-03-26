package org.opencds.cqf.cql.ls.server.repository.ig.standard

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.repository.IRepository
import ca.uhn.fhir.rest.api.EncodingEnum
import org.hl7.fhir.instance.model.api.IIdType
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Library
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.ValueSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.fhir.test.Resources
import org.opencds.cqf.fhir.utility.Ids
import org.opencds.cqf.fhir.utility.search.Searches
import java.nio.file.Files
import java.nio.file.Path

class XmlWriteTest {
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
            Resources.copyFromJar("/sampleIgs/ig/standard/mixedEncoding", tempDir!!)
            val conventions = IgStandardConventions.autoDetect(tempDir!!)
            repository =
                IgStandardRepository(
                    FhirContext.forR4Cached(),
                    tempDir!!,
                    conventions,
                    IgStandardEncodingBehavior(
                        EncodingEnum.XML,
                        IgStandardEncodingBehavior.PreserveEncoding.PRESERVE_ORIGINAL_ENCODING,
                    ),
                    null,
                )
        }
    }

    @Test
    fun createAndDeleteLibrary() {
        val lib = Library()
        lib.id = "new-library"
        val o = repository.create(lib)
        val created = repository.read(Library::class.java, o.id!!)
        assertNotNull(created)

        val loc = tempDir!!.resolve("resources/library/new-library.xml")
        assertTrue(Files.exists(loc))

        repository.delete(Library::class.java, created.idElement)
        assertFalse(Files.exists(loc))
    }

    @Test
    fun createAndDeletePatient() {
        val p = Patient()
        p.id = "new-patient"
        val o = repository.create(p)
        val created = repository.read(Patient::class.java, o.id!!)
        assertNotNull(created)

        val loc = tempDir!!.resolve("tests/patient/new-patient.xml")
        assertTrue(Files.exists(loc))

        repository.delete(Patient::class.java, created.idElement)
        assertFalse(Files.exists(loc))
    }

    @Test
    fun readExternalValueSet() {
        val id: IIdType = Ids.newId(ValueSet::class.java, "789")
        val vs = repository.read(ValueSet::class.java, id)
        assertNotNull(vs)
        assertEquals(vs.idElement.idPart, vs.idElement.idPart)

        // Should be tagged with its source path
        val path = vs.getUserData(IgStandardRepository.SOURCE_PATH_TAG) as Path
        assertNotNull(path)
        assertTrue(path.toFile().exists())
        assertTrue(path.toString().contains("external"))
    }

    @Test
    fun searchExternalValueSet() {
        val sets = repository.search(Bundle::class.java, ValueSet::class.java, Searches.byUrl("example.com/ValueSet/789"))
        assertNotNull(sets)
        assertEquals(1, sets.entry.size)
    }
}
