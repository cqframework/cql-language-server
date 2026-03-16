package org.opencds.cqf.cql.ls.server.manager

import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent
import org.opencds.cqf.cql.ls.server.service.TestContentService
import java.io.InputStream
import java.net.URI

class IgContextManagerTest {

    private lateinit var manager: IgContextManager

    @BeforeEach
    fun setUp() {
        manager = IgContextManager(TestContentService())
    }

    // -----------------------------------------------------------------------
    // getContext — no ig.ini in fixture classpath paths → returns null
    // -----------------------------------------------------------------------

    @Test
    fun getContext_noIgIni_returnsNull() {
        val result = manager.getContext(TEST_URI)
        assertNull(result)
    }

    // -----------------------------------------------------------------------
    // getContext — result is cached (content service not re-read on second call)
    // -----------------------------------------------------------------------

    @Test
    fun getContext_secondCall_returnsSameNullResult() {
        val first = manager.getContext(TEST_URI)
        val second = manager.getContext(TEST_URI)
        assertEquals(first, second)
    }

    @Test
    fun getContext_cachedResult_doesNotReReadContentService() {
        var readCount = 0
        val countingCs = object : ContentService {
            override fun locate(root: URI, libraryIdentifier: VersionedIdentifier): Set<URI> = emptySet()
            override fun read(uri: URI): InputStream? {
                readCount++
                return null
            }
        }
        val localManager = IgContextManager(countingCs)

        localManager.getContext(TEST_URI) // populates cache
        val countAfterFirst = readCount
        localManager.getContext(TEST_URI) // should hit cache

        assertEquals(countAfterFirst, readCount, "Second getContext() call should not read from content service again")
    }

    // -----------------------------------------------------------------------
    // onMessageEvent — ig.ini change clears cache so next call re-reads
    // -----------------------------------------------------------------------

    @Test
    fun onMessageEvent_igIniChanged_clearsCache() {
        var readCount = 0
        val countingCs = object : ContentService {
            override fun locate(root: URI, libraryIdentifier: VersionedIdentifier): Set<URI> = emptySet()
            override fun read(uri: URI): InputStream? {
                readCount++
                return null
            }
        }
        val localManager = IgContextManager(countingCs)

        localManager.getContext(TEST_URI) // populates cache
        val countAfterFirst = readCount

        val igIniUri = Uris.getHead(TEST_URI).toString() + "/ig.ini"
        localManager.onMessageEvent(
            DidChangeWatchedFilesEvent(
                DidChangeWatchedFilesParams(listOf(FileEvent(igIniUri, FileChangeType.Changed)))
            )
        )

        localManager.getContext(TEST_URI) // cache cleared → re-reads
        assertTrue(readCount > countAfterFirst, "Expected content service to be re-read after ig.ini change")
    }

    @Test
    fun onMessageEvent_unrelatedFile_doesNotClearCache() {
        var readCount = 0
        val countingCs = object : ContentService {
            override fun locate(root: URI, libraryIdentifier: VersionedIdentifier): Set<URI> = emptySet()
            override fun read(uri: URI): InputStream? {
                readCount++
                return null
            }
        }
        val localManager = IgContextManager(countingCs)

        localManager.getContext(TEST_URI) // populates cache
        val countAfterFirst = readCount

        localManager.onMessageEvent(
            DidChangeWatchedFilesEvent(
                DidChangeWatchedFilesParams(listOf(FileEvent("file:///workspace/SomeOther.json", FileChangeType.Changed)))
            )
        )

        localManager.getContext(TEST_URI) // should still be cached
        assertEquals(countAfterFirst, readCount, "Unrelated file change should not clear cache")
    }

    companion object {
        // A URI whose parent dir is /org/opencds/cqf/cql/ls/server/ —
        // TestContentService returns null for the ig.ini probe, so NpmProcessor is never created.
        private val TEST_URI: URI = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
    }
}
