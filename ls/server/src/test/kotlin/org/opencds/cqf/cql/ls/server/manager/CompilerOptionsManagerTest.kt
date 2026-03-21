package org.opencds.cqf.cql.ls.server.manager

import org.cqframework.cql.cql2elm.CqlCompilerOptions
import org.cqframework.cql.cql2elm.LibraryBuilder.SignatureLevel
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent
import org.opencds.cqf.cql.ls.server.service.TestContentService
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class CompilerOptionsManagerTest {
    private lateinit var manager: CompilerOptionsManager

    @BeforeEach
    fun setUp() {
        val cs: ContentService = TestContentService()
        manager = CompilerOptionsManager(cs)
    }

    // -----------------------------------------------------------------------
    // getOptions — returns options enriched with required flags
    // -----------------------------------------------------------------------

    @Test
    fun getOptions_noOptionsFile_returnsNonNull() {
        val options = manager.getOptions(TEST_URI)
        assertNotNull(options)
    }

    @Test
    fun getOptions_alwaysIncludesEnableLocators() {
        val options = manager.getOptions(TEST_URI)
        assertTrue(options.options.contains(CqlCompilerOptions.Options.EnableLocators))
    }

    @Test
    fun getOptions_alwaysIncludesEnableResultTypes() {
        val options = manager.getOptions(TEST_URI)
        assertTrue(options.options.contains(CqlCompilerOptions.Options.EnableResultTypes))
    }

    @Test
    fun getOptions_alwaysIncludesEnableAnnotations() {
        val options = manager.getOptions(TEST_URI)
        assertTrue(options.options.contains(CqlCompilerOptions.Options.EnableAnnotations))
    }

    @Test
    fun getOptions_signatureLevelIsAll() {
        val options = manager.getOptions(TEST_URI)
        assertEquals(SignatureLevel.All, options.signatureLevel)
    }

    // -----------------------------------------------------------------------
    // caching — same instance returned on second call
    // -----------------------------------------------------------------------

    @Test
    fun getOptions_secondCall_returnsCachedInstance() {
        val first = manager.getOptions(TEST_URI)
        val second = manager.getOptions(TEST_URI)
        assertSame(first, second)
    }

    // -----------------------------------------------------------------------
    // clearOptions — evicts cache so next call re-reads
    // -----------------------------------------------------------------------

    @Test
    fun clearOptions_evictsCache() {
        manager.getOptions(TEST_URI) // populate cache
        manager.clearOptions(TEST_URI)
        val second = manager.getOptions(TEST_URI)
        assertTrue(second.options.contains(CqlCompilerOptions.Options.EnableLocators))
    }

    // -----------------------------------------------------------------------
    // onMessageEvent — cql-options.json change clears cache
    // -----------------------------------------------------------------------

    @Test
    fun onMessageEvent_cqlOptionsChanged_clearsCache() {
        manager.getOptions(TEST_URI) // populate cache

        val optionsUri = Uris.getHead(TEST_URI).toString() + "/cql/cql-options.json"
        val fileEvent = FileEvent(optionsUri, FileChangeType.Changed)
        val params = DidChangeWatchedFilesParams(listOf(fileEvent))
        manager.onMessageEvent(DidChangeWatchedFilesEvent(params))

        val second = manager.getOptions(TEST_URI)
        assertNotNull(second)
        assertTrue(second.options.contains(CqlCompilerOptions.Options.EnableLocators))
    }

    @Test
    fun onMessageEvent_unrelatedFile_doesNotClearCache() {
        val first = manager.getOptions(TEST_URI)

        val fileEvent = FileEvent("file:///workspace/SomeOtherFile.json", FileChangeType.Changed)
        val params = DidChangeWatchedFilesParams(listOf(fileEvent))
        manager.onMessageEvent(DidChangeWatchedFilesEvent(params))

        val second = manager.getOptions(TEST_URI)
        assertSame(first, second)
    }

    // -----------------------------------------------------------------------
    // readOptions — loads cql-options.json from filesystem via Paths.get(URI)
    //
    // Regression: the old code used uri.toURL().path which returns "/C:/foo" on
    // Windows (leading slash), making the path invalid.  The fix uses
    // Paths.get(URI).toString() which produces the correct OS-native path.
    //
    // Platform   | file URI                               | Paths.get().toString()
    // -----------|----------------------------------------|----------------------
    // macOS/Linux| file:///tmp/test/cql/cql-options.json  | /tmp/test/cql/cql-options.json
    // Windows    | file:///C:/tmp/test/cql/cql-options.json| C:\tmp\test\cql\cql-options.json
    // -----------------------------------------------------------------------

    @Test
    fun getOptions_withRealFileOnDisk_loadsOptionsFromFile(@TempDir tempDir: Path) {
        // Arrange: write a minimal (empty) cql-options.json.
        // Structure: tempDir/cql/cql-options.json
        // getOptions(tempDir/One.cql) → getHead → tempDir/ → readOptions → looks for tempDir/cql/cql-options.json
        val cqlDir = tempDir.resolve("cql")
        Files.createDirectories(cqlDir)
        // Empty JSON object — no DisableListDemotion / DisableListPromotion options.
        // These ARE included by CqlCompilerOptions.defaultOptions(), which readOptions uses
        // when file loading fails. Their absence in the result is the distinguishing signal.
        cqlDir.resolve("cql-options.json").toFile().writeText("{}")

        // ContentService that reads from the real filesystem using the file:// URI.
        // This exercises the Paths.get(URI).toString() fix (regression: toURL().path gave
        // "/C:/foo" on Windows with a leading slash, making the path unresolvable).
        val fsContentService = object : ContentService {
            override fun locate(root: URI, libraryIdentifier: VersionedIdentifier): Set<URI> = emptySet()
            override fun read(uri: URI): InputStream? =
                try { Paths.get(uri).toFile().takeIf { it.exists() }?.inputStream() }
                catch (e: Exception) { null }
        }

        // Act: getOptions(cqlFile) → getHead(cqlFile) = tempDir/ → readOptions(tempDir/) →
        //      Paths.get(optionsUri).toString() → filesystem path → CqlTranslatorOptions.fromFile
        val localManager = CompilerOptionsManager(fsContentService)
        val options = localManager.getOptions(tempDir.resolve("One.cql").toUri())

        // Assert: DisableListDemotion is in CqlCompilerOptions.defaultOptions() but not in "{}".
        // Its absence proves the file was successfully loaded via the fixed Paths.get(URI) path.
        // If the old toURL().path bug were present, fromFile() would fail, the exception would
        // be swallowed, and readOptions() would fall back to defaultOptions() — which DOES include
        // DisableListDemotion.
        assertNotNull(options)
        assertFalse(
            options.options.contains(CqlCompilerOptions.Options.DisableListDemotion),
            "DisableListDemotion is only present when defaultOptions() fallback is used " +
                "(i.e. file loading failed due to wrong path); its absence proves the file was read",
        )
    }

    companion object {
        // A URI whose "head" (parent path) is /org/opencds/cqf/cql/ls/server/
        // TestContentService.read will be called for the cql-options.json path, returning null
        // (no such classpath resource), so default options are used.
        private val TEST_URI: URI = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
    }
}
