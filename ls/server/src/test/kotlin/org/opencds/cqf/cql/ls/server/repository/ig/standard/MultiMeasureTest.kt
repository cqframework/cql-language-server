package org.opencds.cqf.cql.ls.server.repository.ig.standard

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.repository.IRepository
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException
import org.hl7.fhir.instance.model.api.IIdType
import org.hl7.fhir.r4.model.Library
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.fhir.test.Resources
import org.opencds.cqf.fhir.utility.Ids
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

class MultiMeasureTest {
    companion object {
        private val log: Logger = LoggerFactory.getLogger(MultiMeasureTest::class.java)

        private const val ROOT_DIR = "/sampleIgs/ig/standard/cqlMeasures/multiMeasure"
        private const val MODEL_PATH_MEASURE_100_TEST_CASE_1111 = "input/tests/measure/measure100/1111"
        private const val MODEL_PATH_MEASURE_100_TEST_CASE_2222 = "input/tests/measure/measure100/2222"
        private const val MODEL_PATH_MEASURE_200_TEST_CASE_1111 = "input/tests/measure/measure200/1111"
        private const val TERMINOLOGY_PATH = "input/vocabulary/valueset"

        @TempDir
        @JvmField
        var tempDir: Path? = null

        @TempDir
        @JvmField
        var pathModelPathMeasure100TestCase1111TempDir: Path? = null

        @TempDir
        @JvmField
        var pathModelPathMeasure100TestCase2222TempDir: Path? = null

        @TempDir
        @JvmField
        var pathModelPathMeasure200TestCase1111TempDir: Path? = null

        @TempDir
        @JvmField
        var pathTerminologyTempDir: Path? = null

        lateinit var model1111Measure100Repo: IRepository
        lateinit var model2222Measure100Repo: IRepository
        lateinit var model1111Measure200Repo: IRepository
        lateinit var terminologyRepo: IRepository

        fun listFiles(path: Path?) {
            if (path == null) return
            val pathExists = path.toFile().exists()
            log.info("path[{}] exists: {}", path, pathExists)
            if (pathExists) {
                try {
                    Files.walk(path).use { stream ->
                        val fileNames = stream.map { it.toString() }.collect(Collectors.joining("\n  "))
                        log.info("resources: \n  {}", fileNames)
                    }
                } catch (e: IOException) {
                    log.error("Exception while capturing filenames. {}", e.message)
                }
            }
        }

        @BeforeAll
        @JvmStatic
        fun setup() {
            // This copies the sample IG to a temporary directory so that
            // we can test against an actual filesystem
            Resources.copyFromJar(ROOT_DIR, tempDir!!)

            val pathMeasure100Case1111 = tempDir!!.resolve(MODEL_PATH_MEASURE_100_TEST_CASE_1111)
            val pathMeasure100Case2222 = tempDir!!.resolve(MODEL_PATH_MEASURE_100_TEST_CASE_2222)
            val pathMeasure200Case1111 = tempDir!!.resolve(MODEL_PATH_MEASURE_200_TEST_CASE_1111)
            val pathTerminology = tempDir!!.resolve(TERMINOLOGY_PATH)

            model1111Measure100Repo = IgStandardRepository(FhirContext.forR4Cached(), pathMeasure100Case1111)
            model2222Measure100Repo = IgStandardRepository(FhirContext.forR4Cached(), pathMeasure100Case2222)
            model1111Measure200Repo = IgStandardRepository(FhirContext.forR4Cached(), pathMeasure200Case1111)
            terminologyRepo = IgStandardRepository(FhirContext.forR4Cached(), pathTerminology)

            listFiles(tempDir)
            listFiles(pathMeasure100Case1111)
            listFiles(pathMeasure100Case2222)
            listFiles(pathMeasure200Case1111)
            listFiles(pathTerminology)
        }
    }

    @Test
    fun should_throwException_when_libraryDoesNotExist() {
        val id: IIdType = Ids.newId(Library::class.java, "DoesNotExist")
        assertThrows(ResourceNotFoundException::class.java) { model1111Measure100Repo.read(Library::class.java, id) }
        assertThrows(ResourceNotFoundException::class.java) { model2222Measure100Repo.read(Library::class.java, id) }
        assertThrows(ResourceNotFoundException::class.java) { model1111Measure200Repo.read(Library::class.java, id) }
        assertThrows(ResourceNotFoundException::class.java) { terminologyRepo.read(Library::class.java, id) }
    }

    // Test works locally but doesn't work on GitHub
    // @Disabled("Disabled until issue with running test on github is resolved.")
    @Test
    fun should_findResourceInCorrectRepo_when_resourcesIsolatedByRepo() {
        val id: IIdType = Ids.newId(Patient::class.java, "1111")
        val patientFrommModel1111Measure100Repo = model1111Measure100Repo.read(Patient::class.java, id)
        val patientFrommModel1111Measure200Repo = model1111Measure200Repo.read(Patient::class.java, id)

        assertEquals(
            id.idPart,
            patientFrommModel1111Measure100Repo.idElement.idPart,
        )
        assertEquals(
            id.idPart,
            patientFrommModel1111Measure200Repo.idElement.idPart,
        )
        assertNotEquals(patientFrommModel1111Measure100Repo.name, patientFrommModel1111Measure200Repo.name)
    }
}
