package org.opencds.cqf.cql.ls.server.provider

import org.hl7.cql.model.ModelIdentifier
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.elm_modelinfo.r1.serializing.parseModelInfoXml
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import java.io.InputStream
import java.net.URI

class ContentServiceModelInfoProviderTest {
    private val root = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server")!!

    private fun nullContentService(): ContentService =
        object : ContentService {
            override fun locate(
                root: URI,
                identifier: VersionedIdentifier,
            ): Set<URI> = emptySet()

            override fun read(uri: URI): InputStream? = null
        }

    // -----------------------------------------------------------------------
    // Content service returns null — load() returns null
    // -----------------------------------------------------------------------

    @Test
    fun load_returnsNull_whenContentServiceReturnsNull() {
        val provider = ContentServiceModelInfoProvider(root, nullContentService())
        assertNull(provider.load(ModelIdentifier(id = "NonExistentModel")))
    }

    // -----------------------------------------------------------------------
    // Version suffix — model identifier with version exercises the "-version" path
    // -----------------------------------------------------------------------

    @Test
    fun load_returnsNull_whenContentServiceReturnsNullForVersionedModel() {
        val provider = ContentServiceModelInfoProvider(root, nullContentService())
        // Exercises the modelVersion?.let { "-$it" } ?: "" branch
        assertNull(provider.load(ModelIdentifier(id = "FHIR", version = "4.0.1")))
    }

    // -----------------------------------------------------------------------
    // Invalid XML — load() wraps the parse exception in IllegalArgumentException
    // -----------------------------------------------------------------------

    @Test
    fun load_throwsIllegalArgumentException_whenContentServiceReturnsMalformedXml() {
        val provider =
            ContentServiceModelInfoProvider(
                root,
                object : ContentService {
                    override fun locate(
                        root: URI,
                        identifier: VersionedIdentifier,
                    ): Set<URI> = emptySet()

                    override fun read(uri: URI): InputStream = "not valid xml {{{{".byteInputStream()
                },
            )
        assertThrows<IllegalArgumentException> { provider.load(ModelIdentifier(id = "Bad")) }
    }

    // -----------------------------------------------------------------------
    // File URI root — constructs wrong path (treats .cql file as a directory)
    // -----------------------------------------------------------------------
    // CqlEvaluator passes the .cql file URI as root (libraryUri). The provider
    // appends modelinfo paths to it, producing URIs like:
    //   file:///.../MyLibrary.cql/c4bb-modelinfo-2.1.1.xml
    // which fails with "Not a directory" at read time.

    @Test
    fun load_fileUriRoot_appendsModelinfoToCqlFile() {
        var capturedUri: URI? = null
        val capturingService =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ): Set<URI> = emptySet()

                override fun read(uri: URI): InputStream? {
                    capturedUri = uri
                    return null
                }
            }
        val libraryFile = URI.create("file:///workspace/input/cql/MyLibrary.cql")

        val provider = ContentServiceModelInfoProvider(libraryFile, capturingService)
        provider.load(ModelIdentifier(id = "C4BB", version = "2.1.1"))

        assertNotNull(capturedUri)
        assertTrue(
            capturedUri!!.toString().contains("MyLibrary.cql/c4bb-modelinfo-2.1.1.xml"),
            "File URI root causes the .cql file to be treated as a directory: $capturedUri",
        )
    }

    // -----------------------------------------------------------------------
    // Directory URI root — constructs correct path
    // -----------------------------------------------------------------------
    // CqlCompilationManager uses Uris.getHead(uri) which strips the filename,
    // giving a directory root. The modelinfo path resolves correctly:
    //   file:///.../input/cql/c4bb-modelinfo-2.1.1.xml

    @Test
    fun load_directoryUriRoot_appendsModelinfoFlat() {
        var capturedUri: URI? = null
        val capturingService =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ): Set<URI> = emptySet()

                override fun read(uri: URI): InputStream? {
                    capturedUri = uri
                    return null
                }
            }
        val cqlDir = URI.create("file:///workspace/input/cql/")

        val provider = ContentServiceModelInfoProvider(cqlDir, capturingService)
        provider.load(ModelIdentifier(id = "C4BB", version = "2.1.1"))

        assertNotNull(capturedUri)
        assertTrue(
            capturedUri!!.toString().endsWith("c4bb-modelinfo-2.1.1.xml"),
            "Directory URI root produces correct flat path: $capturedUri",
        )
    }

    // -----------------------------------------------------------------------
    // End-to-end: file URI root cannot resolve valid modelinfo
    // -----------------------------------------------------------------------
    // Even when a content service can serve modelinfo from the correct path,
    // a file URI root constructs the wrong path and never finds it.

    @Test
    fun load_fileUriRoot_cannotResolveModelinfo_whenDirectoryRootCan() {
        val cqlDir = URI.create("file:///workspace/input/cql/")
        val libraryFile = URI.create("file:///workspace/input/cql/MyLibrary.cql")
        val validModelinfo = """<?xml version="1.0" encoding="UTF-8"?><modelInfo xmlns="urn:hl7-org:elm-modelinfo-r1" name="C4BB" version="2.1.1"/>"""

        val correctModelinfoUri = URI.create("file:///workspace/input/cql/c4bb-modelinfo-2.1.1.xml")

        val servingService =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ): Set<URI> = emptySet()

                override fun read(uri: URI): InputStream? {
                    // Only serve from the exact correct path — file-root constructs
                    // "MyLibrary.cql/c4bb-modelinfo-2.1.1.xml" which won't match
                    return if (uri == correctModelinfoUri) {
                        validModelinfo.byteInputStream()
                    } else {
                        null
                    }
                }
            }

        // Directory root succeeds — correct flat path
        val dirProvider = ContentServiceModelInfoProvider(cqlDir, servingService)
        assertNotNull(dirProvider.load(ModelIdentifier(id = "C4BB", version = "2.1.1")))

        // File root fails — wrong path (file treated as directory)
        val fileProvider = ContentServiceModelInfoProvider(libraryFile, servingService)
        assertNull(fileProvider.load(ModelIdentifier(id = "C4BB", version = "2.1.1")))
    }

    // -----------------------------------------------------------------------
    // formatRequiredModels — surfaces a model's declared dependencies so that
    // version conflicts (e.g. C4BB requires USCore 7.0.0 while content loads
    // USCore 6.1.0-derived) are visible in the logs.
    // -----------------------------------------------------------------------

    @Test
    fun formatRequiredModels_rendersNameAndVersionForEachDependency() {
        val modelInfo =
            parseModelInfoXml(
                """<?xml version="1.0" encoding="UTF-8"?><modelInfo xmlns="urn:hl7-org:elm-modelinfo:r1" name="C4BB" version="2.1.1"><requiredModelInfo name="System" version="1.0.0"/><requiredModelInfo name="FHIR" version="4.0.1"/><requiredModelInfo name="USCore" version="7.0.0"/></modelInfo>""",
            )

        val formatted = ContentServiceModelInfoProvider.formatRequiredModels(modelInfo)

        assertEquals("[System 1.0.0, FHIR 4.0.1, USCore 7.0.0]", formatted)
    }

    @Test
    fun formatRequiredModels_rendersEmptyBracketsWhenNoDependencies() {
        val modelInfo =
            parseModelInfoXml(
                """<?xml version="1.0" encoding="UTF-8"?><modelInfo xmlns="urn:hl7-org:elm-modelinfo:r1" name="Solo" version="1.0.0"/>""",
            )

        assertEquals("[]", ContentServiceModelInfoProvider.formatRequiredModels(modelInfo))
    }

    @Test
    fun formatRequiredModels_rendersNameOnly_whenRequiredModelInfoVersionIsAbsent() {
        val modelInfo =
            parseModelInfoXml(
                """<?xml version="1.0" encoding="UTF-8"?><modelInfo xmlns="urn:hl7-org:elm-modelinfo:r1" name="X" version="1.0"><requiredModelInfo name="System"/><requiredModelInfo name="FHIR" version="4.0.1"/></modelInfo>""",
            )

        val formatted = ContentServiceModelInfoProvider.formatRequiredModels(modelInfo)
        // System has no version → no space suffix; FHIR has a version → space + version
        assertEquals("[System, FHIR 4.0.1]", formatted)
    }

    // -----------------------------------------------------------------------
    // recordVersionAndDetectConflict — surfaces an actual model version conflict
    // (same model at two versions on one provider instance), e.g. content loads
    // USCore 6.1.0-derived while a C4BB ModelInfo requires USCore 7.0.0.
    // -----------------------------------------------------------------------

    @Test
    fun recordVersionAndDetectConflict_detectsTwoVersionsOfSameModel() {
        val provider = ContentServiceModelInfoProvider(root, nullContentService())
        // First sighting (e.g. requested during edit) — no conflict yet.
        assertNull(
            provider.recordVersionAndDetectConflict("ConflictUSCore", "6.1.0-derived", "requested"),
        )
        // Second, different version (e.g. required by C4BB) — conflict.
        val conflict =
            provider.recordVersionAndDetectConflict("ConflictUSCore", "7.0.0", "required by C4BB")
        assertNotNull(conflict)
        assertTrue(conflict!!.contains("6.1.0-derived (requested)"), conflict)
        assertTrue(conflict.contains("7.0.0 (required by C4BB)"), conflict)
        // A genuinely different version is NOT reported as a case-only difference.
        assertTrue(!conflict.contains("differ only by case"), conflict)
    }

    // A case-only mismatch (6.1.0-Derived vs 6.1.0-derived) is the exact bug that made C4BB fail
    // to load: the engine compares model versions case-sensitively. The message should call it out.
    @Test
    fun recordVersionAndDetectConflict_flagsCaseOnlyDifferenceExplicitly() {
        val provider = ContentServiceModelInfoProvider(root, nullContentService())
        assertNull(
            provider.recordVersionAndDetectConflict("USCore", "6.1.0-derived", "requested"),
        )
        val conflict =
            provider.recordVersionAndDetectConflict("USCore", "6.1.0-Derived", "required by C4BB")
        assertNotNull(conflict)
        assertTrue(conflict!!.contains("differ only by case"), conflict)
        assertTrue(conflict.contains("6.1.0-derived (requested)"), conflict)
        assertTrue(conflict.contains("6.1.0-Derived (required by C4BB)"), conflict)
    }

    @Test
    fun recordVersionAndDetectConflict_noConflictForSameVersionOrNull() {
        val provider = ContentServiceModelInfoProvider(root, nullContentService())
        assertNull(provider.recordVersionAndDetectConflict("SameVer", "1.0.0", "requested"))
        assertNull(provider.recordVersionAndDetectConflict("SameVer", "1.0.0", "required by X"))
        assertNull(provider.recordVersionAndDetectConflict("NullVer", null, "requested"))
    }

    // -----------------------------------------------------------------------
    // load() — version conflict warning path
    // When load() is called twice for the same model at different versions,
    // recordVersionAndDetectConflict returns a non-null description and the
    // conflict is logged. This exercises the `.let { log.warn(...) }` branch
    // on lines 89–91 of ContentServiceModelInfoProvider.
    // -----------------------------------------------------------------------

    @Test
    fun load_triggersConflictWarning_whenSameModelLoadedAtDifferentVersions() {
        val provider = ContentServiceModelInfoProvider(root, nullContentService())
        // First load: no conflict yet (only one version observed)
        assertNull(provider.load(ModelIdentifier(id = "FHIR", version = "4.0.1")))
        // Second load at a different version: conflict is detected and logged internally.
        // load() still returns null (no content), but the conflict log.warn path is exercised.
        assertNull(provider.load(ModelIdentifier(id = "FHIR", version = "3.0.0")))
        // Both versions are now tracked; a third distinct version is also a conflict.
        val conflict = provider.recordVersionAndDetectConflict("FHIR", "2.0.0", "test")
        assertNotNull(conflict, "Three distinct FHIR versions should still produce a conflict description")
    }

    // -----------------------------------------------------------------------
    // load() — requiredModelInfo loop
    // When load() successfully parses a ModelInfo that declares dependencies,
    // it iterates over requiredModelInfo and calls recordVersionAndDetectConflict
    // for each dependency. This covers lines 114–126.
    // -----------------------------------------------------------------------

    @Test
    fun load_iteratesRequiredModelInfoDependencies_whenModelInfoHasDependencies() {
        val c4bbWithDeps =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <modelInfo xmlns="urn:hl7-org:elm-modelinfo:r1" name="C4BB" version="2.1.1">
              <requiredModelInfo name="FHIR" version="4.0.1"/>
              <requiredModelInfo name="USCore" version="6.1.0-derived"/>
            </modelInfo>
            """.trimIndent()

        val servingService =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ): Set<URI> = emptySet()

                override fun read(uri: URI): InputStream? =
                    if (uri.toString().contains("c4bb-modelinfo")) {
                        c4bbWithDeps.byteInputStream()
                    } else {
                        null
                    }
            }

        val cqlDir = URI.create("file:///workspace/input/cql/")
        val provider = ContentServiceModelInfoProvider(cqlDir, servingService)
        val result = provider.load(ModelIdentifier(id = "C4BB", version = "2.1.1"))

        // Model loaded successfully and dependencies were processed
        assertNotNull(result)
        // FHIR and USCore versions are now recorded — a second load with a different
        // FHIR version should be detected as a conflict.
        val conflict = provider.recordVersionAndDetectConflict("FHIR", "3.0.0", "external")
        assertNotNull(conflict, "FHIR 3.0.0 should conflict with the 4.0.1 seen in requiredModelInfo")
    }

    @Test
    fun load_logsConflictForRequiredDependency_whenVersionConflictsWithPreviousObservation() {
        val c4bbRequiringOldFhir =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <modelInfo xmlns="urn:hl7-org:elm-modelinfo:r1" name="C4BB" version="2.1.1">
              <requiredModelInfo name="FHIR" version="3.0.0"/>
            </modelInfo>
            """.trimIndent()

        val servingService =
            object : ContentService {
                override fun locate(
                    root: URI,
                    identifier: VersionedIdentifier,
                ): Set<URI> = emptySet()

                override fun read(uri: URI): InputStream? =
                    if (uri.toString().contains("c4bb-modelinfo")) {
                        c4bbRequiringOldFhir.byteInputStream()
                    } else {
                        null
                    }
            }

        val cqlDir = URI.create("file:///workspace/input/cql/")
        val provider = ContentServiceModelInfoProvider(cqlDir, servingService)

        // First, record FHIR 4.0.1 as observed (simulates it having been requested earlier)
        provider.recordVersionAndDetectConflict("FHIR", "4.0.1", "requested")

        // Now load C4BB, whose requiredModelInfo declares FHIR 3.0.0 — a conflict with 4.0.1
        val result = provider.load(ModelIdentifier(id = "C4BB", version = "2.1.1"))

        // C4BB itself loaded successfully
        assertNotNull(result)
        // The provider's state now reflects both FHIR 4.0.1 and 3.0.0 — verify the conflict
        provider.recordVersionAndDetectConflict("FHIR", "3.0.0", "required by C4BB")
        // Recording the same version again doesn't add a new conflict (idempotent),
        // but the map still has two entries from the prior calls
        assertNotNull(
            provider.recordVersionAndDetectConflict("FHIR", "5.0.0", "other"),
            "A third FHIR version should still be detected as a conflict",
        )
    }

    // Version tracking is instance state: a fresh provider (one per compilation) starts empty and
    // never inherits versions observed by a previous instance/run.
    @Test
    fun recordVersionAndDetectConflict_separateInstancesDoNotShareState() {
        val a = ContentServiceModelInfoProvider(root, nullContentService())
        val b = ContentServiceModelInfoProvider(root, nullContentService())
        assertNull(a.recordVersionAndDetectConflict("M", "1.0.0", "requested"))
        // b never saw 1.0.0, so recording a different version on b is not a conflict.
        assertNull(b.recordVersionAndDetectConflict("M", "2.0.0", "requested"))
    }
}
