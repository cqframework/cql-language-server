package org.opencds.cqf.cql.ls.server.repository.ig.standard

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.repository.IRepository
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException
import java.nio.file.Path
import java.util.stream.Stream
import org.hl7.fhir.instance.model.api.IIdType
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.opencds.cqf.fhir.test.Resources
import org.opencds.cqf.fhir.utility.search.Searches

class BadDataTest {

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
            Resources.copyFromJar("/sampleIgs/ig/standard/badData", tempDir!!)
            repository = IgStandardRepository(FhirContext.forR4Cached(), tempDir!!)
        }

        @JvmStatic
        fun invalidContentTestData(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(IdType("Patient/InvalidContent"), "Found empty or invalid content"),
                Arguments.of(IdType("Patient/MissingId"), "Found resource without an id"),
                Arguments.of(IdType("Patient/NoContent"), "Found empty or invalid content"),
                Arguments.of(IdType("Patient/WrongId"), "Found resource with an id DoesntMatchFilename"),
                Arguments.of(IdType("Patient/WrongResourceType"), "Found resource with type Encounter"),
                Arguments.of(IdType("Patient/WrongVersion").withVersion("1"), "Found resource with version 2")
            )
        }
    }

    @ParameterizedTest
    @MethodSource("invalidContentTestData")
    fun readInvalidContentThrowsException(id: IIdType, errorMessage: String) {
        val e = assertThrows(ResourceNotFoundException::class.java) { repository.read(Patient::class.java, id) }
        assertTrue(e.message!!.contains(errorMessage))
    }

    @Test
    fun nonFhirFilesAreIgnored() {
        val id = IdType("Patient/NotAFhirFile")
        assertThrows(ResourceNotFoundException::class.java) { repository.read(Patient::class.java, id) }
    }

    @Test
    fun searchThrowsBecauseOfInvalidContent() {
        // If there's any invalid content in the directory, the search will fail
        assertThrows(ResourceNotFoundException::class.java) {
            repository.search(Bundle::class.java, Patient::class.java, Searches.ALL)
        }
    }
}
