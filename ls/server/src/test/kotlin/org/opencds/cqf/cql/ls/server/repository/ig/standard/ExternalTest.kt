package org.opencds.cqf.cql.ls.server.repository.ig.standard

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.repository.IRepository
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException
import org.hl7.fhir.instance.model.api.IIdType
import org.hl7.fhir.r4.model.Bundle
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
import org.opencds.cqf.fhir.utility.Ids
import org.opencds.cqf.fhir.utility.search.Searches
import java.nio.file.Files
import java.nio.file.Path

class ExternalTest {
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
            Resources.copyFromJar("/sampleIgs/ig/standard/externalResource", tempDir!!)
            repository = IgStandardRepository(FhirContext.forR4Cached(), tempDir!!)
        }
    }

    @Test
    fun readValueSet() {
        val id: IIdType = Ids.newId(ValueSet::class.java, "456")
        val vs = repository.read(ValueSet::class.java, id)

        assertNotNull(vs)
        assertEquals(vs.idElement.idPart, vs.idElement.idPart)
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

    @Test
    fun updateExternalValueSetFails() {
        val id: IIdType = Ids.newId(ValueSet::class.java, "789")
        val vs = repository.read(ValueSet::class.java, id)
        assertThrows(ForbiddenOperationException::class.java) { repository.update(vs) }
    }
}
