package org.opencds.cqf.cql.ls.server.repository.ig.standard;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.repository.IRepository;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import org.hl7.fhir.r4.model.Library;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencds.cqf.fhir.test.Resources;
import org.opencds.cqf.fhir.utility.Ids;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class MultiMeasureTest {

    private static String resourcePath = "/sample-igs/ig/standard/cql-measures/multi-measure";
    private static IRepository repository;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setup() throws URISyntaxException, IOException, ClassNotFoundException {
        // This copies the sample IG to a temporary directory so that
        // we can test against an actual filesystem
        Resources.copyFromJar(resourcePath, tempDir);
        repository = new IgStandardRepository(FhirContext.forR4Cached(), tempDir);
    }

    @Test
    void readMeasure100Resources() {
        var id = Ids.newId(Library.class, "DoesNotExist");
        assertThrows(ResourceNotFoundException.class, () -> repository.read(Library.class, id));
    }

}
