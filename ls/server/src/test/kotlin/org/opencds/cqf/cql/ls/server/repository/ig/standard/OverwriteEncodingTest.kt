package org.opencds.cqf.cql.ls.server.repository.ig.standard

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.repository.IRepository
import ca.uhn.fhir.rest.api.EncodingEnum
import java.nio.file.Path
import org.hl7.fhir.r4.model.Library
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.fhir.test.Resources
import org.hl7.fhir.instance.model.api.IIdType
import org.opencds.cqf.fhir.utility.Ids

class OverwriteEncodingTest {

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
            repository = IgStandardRepository(
                FhirContext.forR4Cached(),
                tempDir!!,
                conventions,
                IgStandardEncodingBehavior(
                    EncodingEnum.XML,
                    IgStandardEncodingBehavior.PreserveEncoding.OVERWRITE_WITH_PREFERRED_ENCODING
                ),
                null
            )
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
    fun updateLibrary() {
        val id: IIdType = Ids.newId(Library::class.java, "123")
        val lib = repository.read(Library::class.java, id)
        assertNotNull(lib)
        assertEquals(id.idPart, lib.idElement.idPart)

        lib.addAuthor().name = "Test Author"

        repository.update(lib)
        assertFalse(tempDir!!.resolve("resources/library/123.json").toFile().exists())
        assertTrue(tempDir!!.resolve("resources/library/123.xml").toFile().exists())
    }
}
