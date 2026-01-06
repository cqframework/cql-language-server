package org.opencds.cqf.cql.ls.server.repository.ig.standard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencds.cqf.fhir.test.Resources;

class ConventionsTest {

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setup() throws URISyntaxException, IOException, ClassNotFoundException {
        // This copies the sample IG to a temporary directory so that
        // we can test against an actual filesystem
        Resources.copyFromJar("/sampleIgs/ig/standard", tempDir);
    }

    @Test
    void autoDetectDefault() {
        assertEquals(IgStandardConventions.STANDARD, IgStandardConventions.autoDetect(null));
        assertEquals(
                IgStandardConventions.STANDARD, IgStandardConventions.autoDetect(tempDir.resolve("does_not_exist")));
    }

    @Test
    void autoDetectStandard() {
        assertEquals(
                IgStandardConventions.STANDARD,
                IgStandardConventions.autoDetect(tempDir.resolve("directoryPerType/standard")));
    }

    @Test
    void autoDetectPrefix() {
        var config = IgStandardConventions.autoDetect(tempDir.resolve("directoryPerType/prefixed"));
        assertEquals(IgStandardConventions.FilenameMode.TYPE_AND_ID, config.filenameMode());
        assertEquals(IgStandardConventions.CategoryLayout.DIRECTORY_PER_CATEGORY, config.categoryLayout());
        assertEquals(IgStandardConventions.CompartmentLayout.FLAT, config.compartmentLayout());
        assertEquals(IgStandardConventions.FhirTypeLayout.DIRECTORY_PER_TYPE, config.typeLayout());
    }

    @Test
    void autoDetectFlat() {
        assertEquals(IgStandardConventions.FLAT, IgStandardConventions.autoDetect(tempDir.resolve("flat")));
    }

    @Test
    void autoDetectFlatNoTypeNames() {
        var config = IgStandardConventions.autoDetect(tempDir.resolve("flatNoTypeNames"));
        assertEquals(IgStandardConventions.FilenameMode.ID_ONLY, config.filenameMode());
        assertEquals(IgStandardConventions.CategoryLayout.FLAT, config.categoryLayout());
        assertEquals(IgStandardConventions.CompartmentLayout.FLAT, config.compartmentLayout());
        assertEquals(IgStandardConventions.FhirTypeLayout.FLAT, config.typeLayout());
    }

    @Test
    void autoDetectWithMisleadingFileName() {
        assertEquals(
                IgStandardConventions.STANDARD,
                IgStandardConventions.autoDetect(tempDir.resolve("misleadingFileName")));
    }

    @Test
    void autoDetectWithEmptyContent() {
        assertEquals(IgStandardConventions.STANDARD, IgStandardConventions.autoDetect(tempDir.resolve("emptyContent")));
    }

    @Test
    void autoDetectWithNonFhirFilename() {
        assertEquals(
                IgStandardConventions.STANDARD, IgStandardConventions.autoDetect(tempDir.resolve("nonFhirFilename")));
    }

    @Test
    void autoDetectWitCompartments() {
        var config = IgStandardConventions.autoDetect(tempDir.resolve("compartment"));
        assertEquals(IgStandardConventions.FilenameMode.ID_ONLY, config.filenameMode());
        assertEquals(IgStandardConventions.CategoryLayout.DIRECTORY_PER_CATEGORY, config.categoryLayout());
        assertEquals(IgStandardConventions.CompartmentLayout.DIRECTORY_PER_COMPARTMENT, config.compartmentLayout());
        assertEquals(IgStandardConventions.FhirTypeLayout.DIRECTORY_PER_TYPE, config.typeLayout());
    }
}
