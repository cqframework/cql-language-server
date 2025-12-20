package org.opencds.cqf.cql.ls.server.repository.ig.standard;

import static org.junit.jupiter.api.Assertions.*;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.repository.IRepository;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hl7.fhir.r4.model.Library;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencds.cqf.fhir.test.Resources;
import org.opencds.cqf.fhir.utility.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MultiMeasureTest {
    private static final Logger log = LoggerFactory.getLogger(MultiMeasureTest.class);

    private static final String rootDir = "/sampleIgs/ig/standard/cqlMeasures/multiMeasure";
    private static final String modelPathMeasure100TestCase1111 = "input/tests/measure/measure100/1111";
    private static final String modelPathMeasure100TestCase2222 = "input/tests/measure/measure100/2222";
    private static final String modelPathMeasure200TestCase1111 = "input/tests/measure/measure200/1111";
    private static final String terminologyPath = "input/vocabulary/valueset";

    @TempDir
    static Path tempDir;

    @TempDir
    static Path pathModelPathMeasure100TestCase1111;

    @TempDir
    static Path pathModelPathMeasure100TestCase2222;

    @TempDir
    static Path pathModelPathMeasure200TestCase1111;

    @TempDir
    static Path pathTerminology;

    static IRepository model1111Measure100Repo;
    static IRepository model2222Measure100Repo;
    static IRepository model1111Measure200Repo;
    static IRepository terminologyRepo;

    static void listFiles(Path path) {
        var pathExists = path.toFile().exists();
        log.info("path[{}] exists: {}", path, pathExists);
        if (pathExists)
            try (Stream<Path> stream = Files.walk(path)) {
                String fileNames = stream.map(Path::toString).collect(Collectors.joining("\n  "));

                log.info("resources: \n  {}", fileNames);
            } catch (IOException e) {
                log.error("Exception while capturing filenames. {}", e.getMessage());
            }
    }

    @BeforeAll
    static void setup() throws URISyntaxException, IOException, ClassNotFoundException {
        // This copies the sample IG to a temporary directory so that
        // we can test against an actual filesystem
        Resources.copyFromJar(rootDir, tempDir);

        pathModelPathMeasure100TestCase1111 = tempDir.resolve(modelPathMeasure100TestCase1111);
        pathModelPathMeasure100TestCase2222 = tempDir.resolve(modelPathMeasure100TestCase2222);
        pathModelPathMeasure200TestCase1111 = tempDir.resolve(modelPathMeasure200TestCase1111);
        pathTerminology = tempDir.resolve(terminologyPath);

        model1111Measure100Repo =
                new IgStandardRepository(FhirContext.forR4Cached(), pathModelPathMeasure100TestCase1111);
        model2222Measure100Repo =
                new IgStandardRepository(FhirContext.forR4Cached(), pathModelPathMeasure100TestCase2222);
        model1111Measure200Repo =
                new IgStandardRepository(FhirContext.forR4Cached(), pathModelPathMeasure200TestCase1111);
        terminologyRepo = new IgStandardRepository(FhirContext.forR4Cached(), pathTerminology);

        listFiles(tempDir);
        listFiles(pathModelPathMeasure100TestCase1111);
        listFiles(pathModelPathMeasure100TestCase2222);
        listFiles(pathModelPathMeasure200TestCase1111);
        listFiles(pathTerminology);
    }

    @Test
    void should_throwException_when_libraryDoesNotExist() {
        var id = Ids.newId(Library.class, "DoesNotExist");
        assertThrows(ResourceNotFoundException.class, () -> model1111Measure100Repo.read(Library.class, id));
        assertThrows(ResourceNotFoundException.class, () -> model2222Measure100Repo.read(Library.class, id));
        assertThrows(ResourceNotFoundException.class, () -> model1111Measure200Repo.read(Library.class, id));
        assertThrows(ResourceNotFoundException.class, () -> terminologyRepo.read(Library.class, id));
    }

    // Test works locally but doesn't work on GitHub
    // @Disabled("Disabled until issue with running test on github is resolved.")
    @Test
    void should_findResourceInCorrectRepo_when_resourcesIsolatedByRepo() {
        var id = Ids.newId(Patient.class, "1111");
        var patientFrommModel1111Measure100Repo = model1111Measure100Repo.read(Patient.class, id);
        var patientFrommModel1111Measure200Repo = model1111Measure200Repo.read(Patient.class, id);

        assertEquals(
                id.getIdPart(),
                patientFrommModel1111Measure100Repo.getIdElement().getIdPart());
        assertEquals(
                id.getIdPart(),
                patientFrommModel1111Measure200Repo.getIdElement().getIdPart());
        assertNotEquals(patientFrommModel1111Measure100Repo.getName(), patientFrommModel1111Measure200Repo.getName());
    }
}
