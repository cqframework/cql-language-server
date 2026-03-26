package org.opencds.cqf.cql.ls.server.repository.ig.standard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.fhir.test.Resources
import java.nio.file.Path

class ConventionsTest {
    companion object {
        @TempDir
        @JvmField
        var tempDir: Path? = null

        @BeforeAll
        @JvmStatic
        fun setup() {
            // This copies the sample IG to a temporary directory so that
            // we can test against an actual filesystem
            Resources.copyFromJar("/sampleIgs/ig/standard", tempDir!!)
        }
    }

    @Test
    fun autoDetectDefault() {
        assertEquals(IgStandardConventions.STANDARD, IgStandardConventions.autoDetect(null))
        assertEquals(
            IgStandardConventions.STANDARD,
            IgStandardConventions.autoDetect(tempDir!!.resolve("does_not_exist")),
        )
    }

    @Test
    fun autoDetectStandard() {
        assertEquals(
            IgStandardConventions.STANDARD,
            IgStandardConventions.autoDetect(tempDir!!.resolve("directoryPerType/standard")),
        )
    }

    @Test
    fun autoDetectPrefix() {
        val config = IgStandardConventions.autoDetect(tempDir!!.resolve("directoryPerType/prefixed"))
        assertEquals(IgStandardConventions.FilenameMode.TYPE_AND_ID, config.filenameMode)
        assertEquals(IgStandardConventions.CategoryLayout.DIRECTORY_PER_CATEGORY, config.categoryLayout)
        assertEquals(IgStandardConventions.CompartmentLayout.FLAT, config.compartmentLayout)
        assertEquals(IgStandardConventions.FhirTypeLayout.DIRECTORY_PER_TYPE, config.typeLayout)
    }

    @Test
    fun autoDetectFlat() {
        assertEquals(IgStandardConventions.FLAT, IgStandardConventions.autoDetect(tempDir!!.resolve("flat")))
    }

    @Test
    fun autoDetectFlatNoTypeNames() {
        val config = IgStandardConventions.autoDetect(tempDir!!.resolve("flatNoTypeNames"))
        assertEquals(IgStandardConventions.FilenameMode.ID_ONLY, config.filenameMode)
        assertEquals(IgStandardConventions.CategoryLayout.FLAT, config.categoryLayout)
        assertEquals(IgStandardConventions.CompartmentLayout.FLAT, config.compartmentLayout)
        assertEquals(IgStandardConventions.FhirTypeLayout.FLAT, config.typeLayout)
    }

    @Test
    fun autoDetectWithMisleadingFileName() {
        assertEquals(
            IgStandardConventions.STANDARD,
            IgStandardConventions.autoDetect(tempDir!!.resolve("misleadingFileName")),
        )
    }

    @Test
    fun autoDetectWithEmptyContent() {
        assertEquals(IgStandardConventions.STANDARD, IgStandardConventions.autoDetect(tempDir!!.resolve("emptyContent")))
    }

    @Test
    fun autoDetectWithNonFhirFilename() {
        assertEquals(
            IgStandardConventions.STANDARD,
            IgStandardConventions.autoDetect(tempDir!!.resolve("nonFhirFilename")),
        )
    }

    @Test
    fun autoDetectWitCompartments() {
        val config = IgStandardConventions.autoDetect(tempDir!!.resolve("compartment"))
        assertEquals(IgStandardConventions.FilenameMode.ID_ONLY, config.filenameMode)
        assertEquals(IgStandardConventions.CategoryLayout.DIRECTORY_PER_CATEGORY, config.categoryLayout)
        assertEquals(IgStandardConventions.CompartmentLayout.DIRECTORY_PER_COMPARTMENT, config.compartmentLayout)
        assertEquals(IgStandardConventions.FhirTypeLayout.DIRECTORY_PER_TYPE, config.typeLayout)
    }
}
