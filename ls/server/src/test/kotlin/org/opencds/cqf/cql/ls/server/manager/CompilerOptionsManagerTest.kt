package org.opencds.cqf.cql.ls.server.manager

import org.cqframework.cql.cql2elm.CqlCompilerOptions
import org.cqframework.cql.cql2elm.LibraryBuilder.SignatureLevel
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent
import org.opencds.cqf.cql.ls.server.service.TestContentService
import java.net.URI

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

    companion object {
        // A URI whose "head" (parent path) is /org/opencds/cqf/cql/ls/server/
        // TestContentService.read will be called for the cql-options.json path, returning null
        // (no such classpath resource), so default options are used.
        private val TEST_URI: URI = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
    }
}
