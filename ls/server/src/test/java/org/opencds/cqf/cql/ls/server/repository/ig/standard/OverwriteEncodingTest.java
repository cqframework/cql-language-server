package org.opencds.cqf.cql.ls.server.repository.ig.standard;

import static org.junit.jupiter.api.Assertions.*;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.repository.IRepository;
import ca.uhn.fhir.rest.api.EncodingEnum;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import org.hl7.fhir.r4.model.Library;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencds.cqf.fhir.test.Resources;
import org.opencds.cqf.fhir.utility.Ids;

class OverwriteEncodingTest {

    private static IRepository repository;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setup() throws URISyntaxException, IOException, ClassNotFoundException {
        // This copies the sample IG to a temporary directory so that
        // we can test against an actual filesystem
        Resources.copyFromJar("/sampleIgs/ig/standard/mixedEncoding", tempDir);
        var conventions = IgStandardConventions.autoDetect(tempDir);
        repository = new IgStandardRepository(
                FhirContext.forR4Cached(),
                tempDir,
                conventions,
                new IgStandardEncodingBehavior(
                        EncodingEnum.XML,
                        IgStandardEncodingBehavior.PreserveEncoding.OVERWRITE_WITH_PREFERRED_ENCODING),
                null);
    }

    @Test
    void readLibrary() {
        var id = Ids.newId(Library.class, "123");
        var lib = repository.read(Library.class, id);
        assertNotNull(lib);
        assertEquals(id.getIdPart(), lib.getIdElement().getIdPart());
    }

    @Test
    void updateLibrary() {
        var id = Ids.newId(Library.class, "123");
        var lib = repository.read(Library.class, id);
        assertNotNull(lib);
        assertEquals(id.getIdPart(), lib.getIdElement().getIdPart());

        lib.addAuthor().setName("Test Author");

        repository.update(lib);
        assertFalse(tempDir.resolve("resources/library/123.json").toFile().exists());
        assertTrue(tempDir.resolve("resources/library/123.xml").toFile().exists());
    }
}
