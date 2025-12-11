package org.opencds.cqf.cql.ls.server.utility;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hl7.fhir.r4.model.Enumerations.FHIRAllTypes;
import org.opencds.cqf.fhir.utility.repository.ig.CompartmentMode;
import org.opencds.cqf.fhir.utility.repository.ig.EncodingBehavior;
import org.opencds.cqf.fhir.utility.repository.ig.IgConventions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.opencds.cqf.fhir.utility.repository.ig.IgConventions.STANDARD;

public class IgConventionsHelper {

    private static final Logger logger = LoggerFactory.getLogger(IgConventionsHelper.class);

    private static final List<String> FHIR_TYPE_NAMES = Stream.of(FHIRAllTypes.values())
            .map(FHIRAllTypes::name)
            .map(String::toLowerCase)
            .distinct()
            .collect(Collectors.toUnmodifiableList());
    
    /**
     * Auto-detect the IG conventions based on the structure of the IG. If the path is null or the
     * convention can not be reliably detected, the default configuration is returned.
     *
     * @param path The path to the IG.
     * @return The IG conventions.
     */
    public static IgConventions autoDetect(Path path) {

        if (path == null || !Files.exists(path)) {
            return STANDARD;
        }

        // A "category" hierarchy may exist in the ig file structure,
        // where resource categories ("data", "terminology", "content") are organized into
        // subdirectories ("tests", "vocabulary", "resources").
        //
        // e.g. "input/tests", "input/vocabulary".
        //
        // Check all possible category paths and grab the first that exists,
        // or use the IG path if none exist.

        var categoryPath = Stream.of("tests", "vocabulary", "resources")
                .map(path::resolve)
                .filter(x -> x.toFile().exists())
                .findFirst()
                .orElse(path);

        var hasCategoryDirectory = !path.equals(categoryPath);

        var hasCompartmentDirectory = false;

        // Compartments can only exist for test data
        if (hasCategoryDirectory) {
            var tests = path.resolve("tests");
            // A compartment under the tests looks like a set of subdirectories
            // e.g. "input/tests/Patient", "input/tests/Practitioner"
            // that themselves contain subdirectories for each test case.
            // e.g. "input/tests/Patient/test1", "input/tests/Patient/test2"
            // Then within those, the structure may be flat (e.g. "input/tests/Patient/test1/123.json")
            // or grouped by type (e.g. "input/tests/Patient/test1/Patient/123.json").
            //
            // The trick is that the in the case that the test cases are
            // grouped by type, the compartment directory will be the same as the type directory.
            // so we need to look at the resource type directory and check if the contents are files
            // or more directories. If more directories exist, and the directory name is not a
            // FHIR type, then we have a compartment directory.
            if (tests.toFile().exists()) {
                var compartments = FHIR_TYPE_NAMES.stream().map(tests::resolve).filter(x -> x.toFile()
                        .exists());

                final List<Path> compartmentsList = compartments.collect(Collectors.toUnmodifiableList());

                // Check if any of the potential compartment directories
                // have subdirectories that are not FHIR types (e.g. "input/tests/Patient/test1).
                hasCompartmentDirectory = compartmentsList.stream()
                        .flatMap(IgConventionsHelper::listFiles)
                        .filter(Files::isDirectory)
                        .anyMatch(IgConventionsHelper::matchesAnyResource);
            }
        }

        // A "type" may also exist in the igs file structure, where resources
        // are grouped by type into subdirectories.
        //
        // e.g. "input/vocabulary/valueset", "input/resources/valueset".
        //
        // Check all possible type paths and grab the first that exists,
        // or use the category directory if none exist
        var typePath = FHIR_TYPE_NAMES.stream()
                .map(categoryPath::resolve)
                .filter(Files::exists)
                .findFirst()
                .orElse(categoryPath);

        var hasTypeDirectory = !categoryPath.equals(typePath);

        // A file "claims" to be a FHIR resource type if its filename starts with a valid FHIR type name.
        // For files that "claim" to be a FHIR resource type, we check to see if the contents of the file
        // have a resource that matches the claimed type.
        var hasTypeFilename = hasTypeFilename(typePath);

        // Should also check for all the file extension that are used in the IG
        // e.g. .json, .xml, and add them to the enabled encodings.

        var config = new IgConventions(
                hasTypeDirectory ? IgConventions.FhirTypeLayout.DIRECTORY_PER_TYPE : IgConventions.FhirTypeLayout.FLAT,
                hasCategoryDirectory ? IgConventions.CategoryLayout.DIRECTORY_PER_CATEGORY : IgConventions.CategoryLayout.FLAT,
                CompartmentMode.NONE,
                // TODO: Cannot auto-detect this yet, default to FULL
                // We can check for non-compartment resources in compartment directories to detect FHIR vs FULL
                // For example, if we find a Medication resource in a Patient compartment directory,
                // we know it is FULL isolation.
                IgConventions.CompartmentIsolation.FULL,
                hasTypeFilename ? IgConventions.FilenameMode.TYPE_AND_ID : IgConventions.FilenameMode.ID_ONLY,
                EncodingBehavior.DEFAULT);

        logger.info("Auto-detected repository configuration: {}", config);

        return config;
    }

    private static boolean hasTypeFilename(Path typePath) {
        try (var fileStream = Files.list(typePath)) {
            return fileStream
                    .filter(IgConventionsHelper::fileNameMatchesType)
                    .filter(filePath -> claimedFhirType(filePath) != FHIRAllTypes.NULL)
                    .anyMatch(filePath -> contentsMatchClaimedType(filePath, claimedFhirType(filePath)));
        } catch (IOException exception) {
            logger.error("Error listing files in path: {}", typePath, exception);
            return false;
        }
    }

    private static boolean fileNameMatchesType(Path innerFile) {
        Objects.requireNonNull(innerFile);
        var fileName = innerFile.getFileName().toString();
        return FHIR_TYPE_NAMES.stream().anyMatch(type -> fileName.toLowerCase().startsWith(type));
    }

    private static boolean matchesAnyResource(Path innerFile) {
        return !FHIR_TYPE_NAMES.contains(innerFile.getFileName().toString().toLowerCase());
    }

    private static Stream<Path> listFiles(Path innerPath) {
        try {
            return Files.list(innerPath);
        } catch (IOException e) {
            logger.error("Error listing files in path: {}", innerPath, e);
            return Stream.empty();
        }
    }

    // This method checks to see if the contents of a file match the type claimed by the filename
    private static boolean contentsMatchClaimedType(Path filePath, FHIRAllTypes claimedFhirType) {
        Objects.requireNonNull(filePath);
        Objects.requireNonNull(claimedFhirType);

        try {
            var contents = Files.readString(filePath, StandardCharsets.UTF_8);
            if (contents.isBlank()) {
                return false;
            }

            var filename = filePath.getFileName().toString();
            var fileNameWithoutExtension = filename.substring(0, filename.lastIndexOf("."));
            // Check that the contents contain the claimed type, and that the id is not the same as the filename
            // NOTE: This does not work for XML files.
            return contents.toUpperCase().contains(String.format("\"RESOURCETYPE\": \"%s\"", claimedFhirType.name()))
                    && !contents.toUpperCase()
                    .contains(String.format("\"ID\": \"%s\"", fileNameWithoutExtension));

        } catch (IOException e) {
            return false;
        }
    }

    // Detects the FHIR type claimed by the filename
    private static FHIRAllTypes claimedFhirType(Path filePath) {
        var filename = filePath.getFileName().toString();
        if (!filename.contains("-")) {
            return FHIRAllTypes.NULL;
        }

        var codeName = filename.substring(0, filename.indexOf("-")).toUpperCase();
        try {
            return FHIRAllTypes.valueOf(codeName);
        } catch (Exception e) {
            return FHIRAllTypes.NULL;
        }
    }
}
