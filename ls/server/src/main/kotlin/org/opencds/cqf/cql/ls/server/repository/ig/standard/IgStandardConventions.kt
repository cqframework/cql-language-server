package org.opencds.cqf.cql.ls.server.repository.ig.standard

import org.hl7.fhir.r4.model.Enumerations.FHIRAllTypes
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * This class represents the different file structures for an IG repository. The main differences
 * between the various configurations are whether the files are organized by resource type
 * and/or category, and whether the files are prefixed with the resource type.
 */
data class IgStandardConventions(
    val typeLayout: FhirTypeLayout,
    val categoryLayout: CategoryLayout,
    val compartmentLayout: CompartmentLayout,
    val filenameMode: FilenameMode,
) {
    enum class FhirTypeLayout {
        DIRECTORY_PER_TYPE,
        FLAT,
    }

    enum class CategoryLayout {
        DIRECTORY_PER_CATEGORY,
        FLAT,
    }

    enum class CompartmentLayout {
        DIRECTORY_PER_COMPARTMENT,
        FLAT,
    }

    enum class FilenameMode {
        TYPE_AND_ID,
        ID_ONLY,
    }

    override fun toString(): String =
        "IGConventions [typeLayout=$typeLayout, categoryLayout=$categoryLayout compartmentLayout=$compartmentLayout, filenameMode=$filenameMode]"

    companion object {
        private val logger = LoggerFactory.getLogger(IgStandardConventions::class.java)

        val FLAT =
            IgStandardConventions(
                FhirTypeLayout.FLAT,
                CategoryLayout.FLAT,
                CompartmentLayout.FLAT,
                FilenameMode.TYPE_AND_ID,
            )

        val STANDARD =
            IgStandardConventions(
                FhirTypeLayout.DIRECTORY_PER_TYPE,
                CategoryLayout.DIRECTORY_PER_CATEGORY,
                CompartmentLayout.FLAT,
                FilenameMode.ID_ONLY,
            )

        private val FHIR_TYPE_NAMES: List<String> =
            FHIRAllTypes.values()
                .map { it.name.lowercase() }
                .distinct()

        /**
         * Auto-detect the IG conventions based on the structure of the IG.
         */
        fun autoDetect(path: Path?): IgStandardConventions {
            if (path == null || !Files.exists(path)) {
                return STANDARD
            }

            val categoryPath =
                listOf("tests", "vocabulary", "resources")
                    .map { path.resolve(it) }
                    .firstOrNull { it.toFile().exists() }
                    ?: path

            val hasCategoryDirectory = path != categoryPath

            var hasCompartmentDirectory = false

            if (hasCategoryDirectory) {
                val tests = path.resolve("tests")
                if (tests.toFile().exists()) {
                    val compartments = FHIR_TYPE_NAMES.map { tests.resolve(it) }.filter { it.toFile().exists() }

                    hasCompartmentDirectory =
                        compartments
                            .flatMap { listFiles(it).toList() }
                            .filter { Files.isDirectory(it) }
                            .any { matchesAnyResource(it) }
                }
            }

            val typePath =
                FHIR_TYPE_NAMES
                    .map { categoryPath.resolve(it) }
                    .firstOrNull { Files.exists(it) }
                    ?: categoryPath

            val hasTypeDirectory = categoryPath != typePath
            val hasTypeFilename = hasTypeFilename(typePath)

            val config =
                IgStandardConventions(
                    if (hasTypeDirectory) FhirTypeLayout.DIRECTORY_PER_TYPE else FhirTypeLayout.FLAT,
                    if (hasCategoryDirectory) CategoryLayout.DIRECTORY_PER_CATEGORY else CategoryLayout.FLAT,
                    if (hasCompartmentDirectory) CompartmentLayout.DIRECTORY_PER_COMPARTMENT else CompartmentLayout.FLAT,
                    if (hasTypeFilename) FilenameMode.TYPE_AND_ID else FilenameMode.ID_ONLY,
                )

            logger.info("Auto-detected repository configuration: {}", config)

            return config
        }

        private fun hasTypeFilename(typePath: Path): Boolean {
            return try {
                Files.list(typePath).use { fileStream ->
                    fileStream
                        .filter { fileNameMatchesType(it) }
                        .filter { claimedFhirType(it) != FHIRAllTypes.NULL }
                        .anyMatch { contentsMatchClaimedType(it, claimedFhirType(it)) }
                }
            } catch (e: IOException) {
                logger.error("Error listing files in path: {}", typePath, e)
                false
            }
        }

        private fun fileNameMatchesType(innerFile: Path): Boolean {
            val fileName = innerFile.fileName.toString()
            return FHIR_TYPE_NAMES.any { type -> fileName.lowercase().startsWith(type) }
        }

        private fun matchesAnyResource(innerFile: Path): Boolean {
            return !FHIR_TYPE_NAMES.contains(innerFile.fileName.toString().lowercase())
        }

        private fun listFiles(innerPath: Path): List<Path> {
            return try {
                Files.list(innerPath).toList()
            } catch (e: IOException) {
                logger.error("Error listing files in path: {}", innerPath, e)
                emptyList()
            }
        }

        private fun contentsMatchClaimedType(
            filePath: Path,
            claimedFhirType: FHIRAllTypes,
        ): Boolean {
            return try {
                val contents = filePath.toFile().readText(Charsets.UTF_8)
                if (contents.isEmpty()) return false

                val filename = filePath.fileName.toString()
                val fileNameWithoutExtension = filename.substring(0, filename.lastIndexOf("."))
                contents.uppercase().contains("\"RESOURCETYPE\": \"${claimedFhirType.name}\"") &&
                    !contents.uppercase().contains("\"ID\": \"${fileNameWithoutExtension.uppercase()}\"")
            } catch (e: IOException) {
                false
            }
        }

        private fun claimedFhirType(filePath: Path): FHIRAllTypes {
            val filename = filePath.fileName.toString()
            if (!filename.contains("-")) return FHIRAllTypes.NULL

            val codeName = filename.substring(0, filename.indexOf("-")).uppercase()
            return try {
                FHIRAllTypes.valueOf(codeName)
            } catch (e: Exception) {
                FHIRAllTypes.NULL
            }
        }
    }
}
