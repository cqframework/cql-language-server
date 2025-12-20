package org.opencds.cqf.cql.ls.server.repository.ig.standard;

import static org.junit.jupiter.api.Assertions.*;

import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import org.hl7.fhir.dstu2.model.ValueSet;
import org.hl7.fhir.dstu3.model.Library;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencds.cqf.fhir.test.Resources;

class CqlContentTest {

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setup() throws ClassNotFoundException, URISyntaxException, IOException {
        // This copies the sample IG to a temporary directory so that
        // we can test against an actual filesystem
        Resources.copyFromJar("/sampleIgs/ig/standard/cqlContent", tempDir);
    }

    @Test
    void loadCqlContentDstu3() {
        var lib = new Library();
        lib.addContent().setContentType("text/cql").setUrl("cql/Test.cql");
        IgStandardCqlContent.loadCqlContent(lib, tempDir);
        assertNotNull(lib.getContentFirstRep().getData());
    }

    @Test
    void loadCqlContentR4() {
        var lib = new org.hl7.fhir.r4.model.Library();
        lib.addContent().setContentType("text/cql").setUrl("cql/Test.cql");
        IgStandardCqlContent.loadCqlContent(lib, tempDir);
        assertNotNull(lib.getContentFirstRep().getData());
    }

    @Test
    void loadCqlContentR5() {
        var lib = new org.hl7.fhir.r5.model.Library();
        lib.addContent().setContentType("text/cql").setUrl("cql/Test.cql");
        IgStandardCqlContent.loadCqlContent(lib, tempDir);
        assertNotNull(lib.getContentFirstRep().getData());
    }

    @Test
    void emptyLibraryDoesNothing() {
        var lib = new Library();
        IgStandardCqlContent.loadCqlContent(lib, tempDir);
        assertEquals(0, lib.getContent().size());
    }

    @Test
    void nonLibraryResourceDoesNotThrow() {
        assertDoesNotThrow(() -> IgStandardCqlContent.loadCqlContent(new ValueSet(), tempDir));
    }

    @Test
    void invalidFhirVersionThrows() {
        var lib = new org.hl7.fhir.r4b.model.Library();
        assertThrows(IllegalArgumentException.class, () -> IgStandardCqlContent.loadCqlContent(lib, tempDir));
    }

    @Test
    void invalidPathThrows() {
        var lib = new org.hl7.fhir.r4.model.Library();
        lib.addContent().setContentType("text/cql").setUrl("not-a-real-path/Test.cql");
        assertThrows(ResourceNotFoundException.class, () -> IgStandardCqlContent.loadCqlContent(lib, tempDir));
    }

    @Test
    void nullThrows() {
        assertThrows(NullPointerException.class, () -> IgStandardCqlContent.loadCqlContent(null, tempDir));

        var lib = new Library();
        assertThrows(NullPointerException.class, () -> IgStandardCqlContent.loadCqlContent(lib, null));
    }
}
