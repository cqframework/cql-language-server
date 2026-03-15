package org.opencds.cqf.cql.ls.server.repository.ig.standard

import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException
import java.nio.file.Path
import org.hl7.fhir.dstu2.model.ValueSet
import org.hl7.fhir.dstu3.model.Library
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.fhir.test.Resources

class CqlContentTest {

    companion object {
        @TempDir
        @JvmField
        var tempDir: Path? = null

        @BeforeAll
        @JvmStatic
        fun setup() {
            // This copies the sample IG to a temporary directory so that
            // we can test against an actual filesystem
            Resources.copyFromJar("/sampleIgs/ig/standard/cqlContent", tempDir!!)
        }
    }

    @Test
    fun loadCqlContentDstu3() {
        val lib = Library()
        lib.addContent().setContentType("text/cql").url = "cql/Test.cql"
        IgStandardCqlContent.loadCqlContent(lib, tempDir!!)
        assertNotNull(lib.contentFirstRep.data)
    }

    @Test
    fun loadCqlContentR4() {
        val lib = org.hl7.fhir.r4.model.Library()
        lib.addContent().setContentType("text/cql").url = "cql/Test.cql"
        IgStandardCqlContent.loadCqlContent(lib, tempDir!!)
        assertNotNull(lib.contentFirstRep.data)
    }

    @Test
    fun loadCqlContentR5() {
        val lib = org.hl7.fhir.r5.model.Library()
        lib.addContent().setContentType("text/cql").url = "cql/Test.cql"
        IgStandardCqlContent.loadCqlContent(lib, tempDir!!)
        assertNotNull(lib.contentFirstRep.data)
    }

    @Test
    fun emptyLibraryDoesNothing() {
        val lib = Library()
        IgStandardCqlContent.loadCqlContent(lib, tempDir!!)
        assertEquals(0, lib.content.size)
    }

    @Test
    fun nonLibraryResourceDoesNotThrow() {
        assertDoesNotThrow { IgStandardCqlContent.loadCqlContent(ValueSet(), tempDir!!) }
    }

    @Test
    fun invalidFhirVersionThrows() {
        val lib = org.hl7.fhir.r4b.model.Library()
        assertThrows(IllegalArgumentException::class.java) { IgStandardCqlContent.loadCqlContent(lib, tempDir!!) }
    }

    @Test
    fun invalidPathThrows() {
        val lib = org.hl7.fhir.r4.model.Library()
        lib.addContent().setContentType("text/cql").url = "not-a-real-path/Test.cql"
        assertThrows(ResourceNotFoundException::class.java) { IgStandardCqlContent.loadCqlContent(lib, tempDir!!) }
    }

    @Suppress("ARGUMENT_TYPE_MISMATCH")
    @Test
    fun nullThrows() {
        @Suppress("ARGUMENT_TYPE_MISMATCH")
        assertThrows(NullPointerException::class.java) {
            @Suppress("ARGUMENT_TYPE_MISMATCH")
            IgStandardCqlContent.loadCqlContent(null as org.hl7.fhir.instance.model.api.IBaseResource?, tempDir!!)
        }

        val lib = Library()
        @Suppress("ARGUMENT_TYPE_MISMATCH")
        assertThrows(NullPointerException::class.java) {
            @Suppress("ARGUMENT_TYPE_MISMATCH")
            IgStandardCqlContent.loadCqlContent(lib, null as Path?)
        }
    }
}
