package org.opencds.cqf.cql.ls.server.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import org.cqframework.cql.cql2elm.CqlCompilerOptions;
import org.cqframework.cql.cql2elm.LibraryBuilder.SignatureLevel;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent;
import org.opencds.cqf.cql.ls.server.service.TestContentService;

class CompilerOptionsManagerTest {

    private CompilerOptionsManager manager;

    // A URI whose "head" (parent path) is /org/opencds/cqf/cql/ls/server/
    // TestContentService.read will be called for the cql-options.json path, returning null
    // (no such classpath resource), so default options are used.
    private static final URI TEST_URI = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql");

    @BeforeEach
    void setUp() {
        ContentService cs = new TestContentService();
        manager = new CompilerOptionsManager(cs);
    }

    // -----------------------------------------------------------------------
    // getOptions — returns options enriched with required flags
    // -----------------------------------------------------------------------

    @Test
    void getOptions_noOptionsFile_returnsNonNull() {
        CqlCompilerOptions options = manager.getOptions(TEST_URI);
        assertNotNull(options);
    }

    @Test
    void getOptions_alwaysIncludesEnableLocators() {
        CqlCompilerOptions options = manager.getOptions(TEST_URI);
        assertTrue(options.getOptions().contains(CqlCompilerOptions.Options.EnableLocators));
    }

    @Test
    void getOptions_alwaysIncludesEnableResultTypes() {
        CqlCompilerOptions options = manager.getOptions(TEST_URI);
        assertTrue(options.getOptions().contains(CqlCompilerOptions.Options.EnableResultTypes));
    }

    @Test
    void getOptions_alwaysIncludesEnableAnnotations() {
        CqlCompilerOptions options = manager.getOptions(TEST_URI);
        assertTrue(options.getOptions().contains(CqlCompilerOptions.Options.EnableAnnotations));
    }

    @Test
    void getOptions_signatureLevelIsAll() {
        CqlCompilerOptions options = manager.getOptions(TEST_URI);
        assertEquals(SignatureLevel.All, options.getSignatureLevel());
    }

    // -----------------------------------------------------------------------
    // caching — same instance returned on second call
    // -----------------------------------------------------------------------

    @Test
    void getOptions_secondCall_returnsCachedInstance() {
        CqlCompilerOptions first = manager.getOptions(TEST_URI);
        CqlCompilerOptions second = manager.getOptions(TEST_URI);
        assertSame(first, second);
    }

    // -----------------------------------------------------------------------
    // clearOptions — evicts cache so next call re-reads
    // -----------------------------------------------------------------------

    @Test
    void clearOptions_evictsCache() {
        manager.getOptions(TEST_URI); // populate cache
        manager.clearOptions(TEST_URI);
        CqlCompilerOptions second = manager.getOptions(TEST_URI);
        // After clearing, a new instance is created and still satisfies the required-flags contract.
        assertTrue(second.getOptions().contains(CqlCompilerOptions.Options.EnableLocators));
    }

    // -----------------------------------------------------------------------
    // onMessageEvent — cql-options.json change clears cache
    // -----------------------------------------------------------------------

    @Test
    void onMessageEvent_cqlOptionsChanged_clearsCache() {
        manager.getOptions(TEST_URI); // populate cache

        // Simulate a file-watch event for a cql-options.json under the same root.
        String optionsUri = Uris.getHead(TEST_URI).toString() + "/cql/cql-options.json";
        FileEvent fileEvent = new FileEvent(optionsUri, FileChangeType.Changed);
        DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(List.of(fileEvent));
        manager.onMessageEvent(new DidChangeWatchedFilesEvent(params));

        CqlCompilerOptions second = manager.getOptions(TEST_URI);
        // After clearing via event, the options must still be enriched.
        assertNotNull(second);
        assertTrue(second.getOptions().contains(CqlCompilerOptions.Options.EnableLocators));
    }

    @Test
    void onMessageEvent_unrelatedFile_doesNotClearCache() {
        CqlCompilerOptions first = manager.getOptions(TEST_URI); // populate cache

        FileEvent fileEvent = new FileEvent("file:///workspace/SomeOtherFile.json", FileChangeType.Changed);
        DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(Collections.singletonList(fileEvent));
        manager.onMessageEvent(new DidChangeWatchedFilesEvent(params));

        CqlCompilerOptions second = manager.getOptions(TEST_URI);
        assertSame(first, second); // cache intact → same instance
    }
}
