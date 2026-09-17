package org.opencds.cqf.cql.debug

import org.eclipse.lsp4j.debug.BreakpointEventArguments
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.opencds.cqf.cql.ls.core.ContentService
import java.net.URI
import java.nio.file.Paths

class BreakpointManagerTest {
    private lateinit var contentService: ContentService
    private lateinit var manager: BreakpointManager

    @BeforeEach
    fun setUp() {
        contentService = mock(ContentService::class.java)
        manager = BreakpointManager(contentService)
    }

    // -- resolveLibraryIdFromPath -------------------------------------------

    @Nested
    inner class ResolveLibraryIdFromPath {
        @Test
        fun `null path returns null`() {
            assertNull(manager.resolveLibraryIdFromPath(null, emptyMap()))
        }

        @Test
        fun `path matching librarySourceMap returns lib id`() {
            val uri = Paths.get("/test/path.cql").toUri()
            val map = mapOf("TestLib" to uri)
            assertEquals("TestLib", manager.resolveLibraryIdFromPath("/test/path.cql", map))
        }

        @Test
        fun `no match returns null`() {
            val uri = Paths.get("/other/path.cql").toUri()
            val map = mapOf("TestLib" to uri)
            assertNull(manager.resolveLibraryIdFromPath("/test/path.cql", map))
        }

        @Test
        fun `path with extra segments normalized`() {
            val uri = Paths.get("/a/b/c.cql").toUri()
            val map = mapOf("Lib" to uri)
            assertEquals("Lib", manager.resolveLibraryIdFromPath("/a/b/c.cql", map))
        }

        // On Windows, File.toURI() produces uppercase drive letters (file:///C:/...)
        // while VS Code sends lowercase (c:\...).  Path.equals() is case-insensitive
        // on Windows, so the lookup must succeed regardless of drive-letter case.
        @Test
        @EnabledOnOs(OS.WINDOWS)
        fun `windows - lowercase path matches uppercase drive letter URI`() {
            // URI produced by File.toURI() uses uppercase C:
            val uri = URI.create("file:///C:/Users/user/project/FHIRHelpers.cql")
            val map = mapOf("FHIRHelpers" to uri)
            // VS Code sends source.path with lowercase c:
            assertEquals("FHIRHelpers", manager.resolveLibraryIdFromPath("c:\\Users\\user\\project\\FHIRHelpers.cql", map))
        }

        @Test
        @EnabledOnOs(OS.WINDOWS)
        fun `windows - forward slash path matches URI`() {
            val uri = URI.create("file:///C:/Users/user/project/FHIRHelpers.cql")
            val map = mapOf("FHIRHelpers" to uri)
            // Windows accepts forward slashes in paths
            assertEquals("FHIRHelpers", manager.resolveLibraryIdFromPath("c:/Users/user/project/FHIRHelpers.cql", map))
        }
    }

    // -- isRelevantSourcePath ------------------------------------------------

    @Nested
    inner class IsRelevantSourcePath {
        @Test
        fun `null relevantLibraryIds returns true`() {
            assertTrue(manager.isRelevantSourcePath("/test.cql", emptyMap(), null))
        }

        @Test
        fun `path in relevant set returns true`() {
            val uri = Paths.get("/test.cql").toUri()
            val map = mapOf("TestLib" to uri)
            assertTrue(manager.isRelevantSourcePath("/test.cql", map, setOf("TestLib")))
        }

        @Test
        fun `path not in relevant set returns false`() {
            val uri = Paths.get("/test.cql").toUri()
            val map = mapOf("TestLib" to uri)
            assertFalse(manager.isRelevantSourcePath("/test.cql", map, setOf("OtherLib")))
        }

        @Test
        fun `non-mapped path returns false`() {
            assertFalse(manager.isRelevantSourcePath("/nope.cql", emptyMap(), setOf("TestLib")))
        }
    }

    // -- resolveSource -------------------------------------------------------

    @Nested
    inner class ResolveSource {
        @Test
        fun `file URI returns path`() {
            val uri = Paths.get("/test.cql").toUri()
            val map = mapOf("TestLib" to uri)
            val source = manager.resolveSource("TestLib", null, null, map, emptyMap())
            assertTrue(source.path.endsWith("test.cql"))
        }

        @Test
        fun `non-file URI uses sourceReference`() {
            val uri = URI.create("https://example.com/lib.cql")
            val map = mapOf("TestLib" to uri)
            val reg = mapOf(42 to org.hl7.elm.r1.VersionedIdentifier().also { it.id = "TestLib" })
            val source = manager.resolveSource("TestLib", null, null, map, reg)
            assertEquals(42, source.sourceReference)
        }

        @Test
        fun `cql-source URI reads ref directly from the URI, even when the registry lookup would miss`() {
            // Regression test: a live debug session showed librarySourceMap holding
            // "cql-source://1" while the identifier-equality registry scan (id.id == libraryId)
            // failed to find the matching entry and fell back to a bogus ref of 0, which was
            // never actually registered — DAP source() then returned empty content. The ref must
            // be read directly out of the URI, which is unambiguous, instead of re-derived.
            val uri = URI.create("cql-source://1")
            val map = mapOf("FHIRHelpers" to uri)
            // Registry deliberately does NOT have an entry whose id.id equals "FHIRHelpers" —
            // simulates the identity-lookup miss observed in the field.
            val reg = mapOf(1 to org.hl7.elm.r1.VersionedIdentifier().also { it.id = "SomethingElse" })
            val source = manager.resolveSource("FHIRHelpers", null, null, map, reg)
            assertEquals(1, source.sourceReference)
        }

        @Test
        fun `cql-source URI ref wins over a registry scan that would return a different key`() {
            val uri = URI.create("cql-source://7")
            val map = mapOf("FHIRHelpers" to uri)
            val reg = mapOf(7 to org.hl7.elm.r1.VersionedIdentifier().also { it.id = "FHIRHelpers" })
            val source = manager.resolveSource("FHIRHelpers", null, null, map, reg)
            assertEquals(7, source.sourceReference)
        }

        @Test
        fun `streaming fallback when uri is null`() {
            val handler =
                mock(StreamingBreakpointHandler::class.java).also {
                    `when`(it.primaryLibraryId).thenReturn("PrimaryLib")
                }
            val source =
                manager.resolveSource(
                    "PrimaryLib",
                    "file:///streaming.cql",
                    handler,
                    emptyMap(),
                    emptyMap(),
                )
            assertTrue(source.path.endsWith("streaming.cql"))
        }

        @Test
        fun `unknown library returns sourceReference 0`() {
            val source = manager.resolveSource("Unknown", null, null, emptyMap(), emptyMap())
            assertEquals(0, source.sourceReference)
        }

        @Test
        fun `unresolved library still gets a legible source name, not blank`() {
            val source = manager.resolveSource("CQMCommon", null, null, emptyMap(), emptyMap())
            assertEquals("CQMCommon.cql", source.name)
        }

        @Test
        fun `empty library id uses streaming fallback`() {
            val handler =
                mock(StreamingBreakpointHandler::class.java).also {
                    `when`(it.primaryLibraryId).thenReturn("PrimaryLib")
                }
            val source =
                manager.resolveSource("", null, handler, emptyMap(), emptyMap())
            assertNull(source.path)
            assertEquals(0, source.sourceReference)
        }
    }

    // -- collectTransitiveIncludes -------------------------------------------

    @Nested
    inner class CollectTransitiveIncludes {
        @Test
        fun `no compiler returns just primary`() {
            val result = manager.collectTransitiveIncludes("Primary", null, null, mutableMapOf())
            assertEquals(setOf("Primary"), result)
        }

        @Test
        fun `compiler with null libraryManager returns just primary`() {
            val compiler = mock(org.cqframework.cql.cql2elm.CqlCompiler::class.java)
            `when`(compiler.libraryManager).thenReturn(null)

            val result = manager.collectTransitiveIncludes("Primary", compiler, null, mutableMapOf())
            assertEquals(setOf("Primary"), result)
        }

        @Test
        fun `identifier with null id is skipped`() {
            val compiler = mock(org.cqframework.cql.cql2elm.CqlCompiler::class.java)
            val libraryManager = mock(org.cqframework.cql.cql2elm.LibraryManager::class.java)
            val unnamedId = org.hl7.elm.r1.VersionedIdentifier()
            `when`(compiler.libraryManager).thenReturn(libraryManager)
            `when`(libraryManager.compiledLibraries).thenReturn(
                linkedMapOf(unnamedId to mock(org.cqframework.cql.cql2elm.model.CompiledLibrary::class.java)),
            )

            val result = manager.collectTransitiveIncludes("Primary", compiler, null, mutableMapOf())
            assertEquals(setOf("Primary"), result)
        }

        @Test
        fun `returns every compiled library regardless of include depth`() {
            // Mirrors Primary -> TJCOverall -> CQMCommon: cql2elm must fully compile the whole
            // include graph to translate Primary, so LibraryManager.compiledLibraries already
            // contains all three, even though CQMCommon is only a transitive (depth-2) include.
            val compiler = mock(org.cqframework.cql.cql2elm.CqlCompiler::class.java)
            val libraryManager = mock(org.cqframework.cql.cql2elm.LibraryManager::class.java)
            val primaryId = org.hl7.elm.r1.VersionedIdentifier().also { it.id = "Primary" }
            val directId = org.hl7.elm.r1.VersionedIdentifier().also { it.id = "TJCOverall" }
            val transitiveId = org.hl7.elm.r1.VersionedIdentifier().also { it.id = "CQMCommon" }
            `when`(compiler.libraryManager).thenReturn(libraryManager)
            `when`(libraryManager.compiledLibraries).thenReturn(
                linkedMapOf(
                    primaryId to mock(org.cqframework.cql.cql2elm.model.CompiledLibrary::class.java),
                    directId to mock(org.cqframework.cql.cql2elm.model.CompiledLibrary::class.java),
                    transitiveId to mock(org.cqframework.cql.cql2elm.model.CompiledLibrary::class.java),
                ),
            )

            val result = manager.collectTransitiveIncludes("Primary", compiler, null, mutableMapOf())

            assertEquals(setOf("Primary", "TJCOverall", "CQMCommon"), result)
        }

        @Test
        fun `resolves and registers a file URI for a transitively-included library`() {
            val compiler = mock(org.cqframework.cql.cql2elm.CqlCompiler::class.java)
            val libraryManager = mock(org.cqframework.cql.cql2elm.LibraryManager::class.java)
            val libId = org.hl7.elm.r1.VersionedIdentifier().also { it.id = "CQMCommon" }
            `when`(compiler.libraryManager).thenReturn(libraryManager)
            `when`(libraryManager.compiledLibraries).thenReturn(
                linkedMapOf(libId to mock(org.cqframework.cql.cql2elm.model.CompiledLibrary::class.java)),
            )
            val resolvedUri = URI.create("file:///CQMCommon.cql")
            `when`(contentService.locate(URI.create("file:///primary.cql"), libId)).thenReturn(setOf(resolvedUri))

            val librarySourceMap = mutableMapOf<String, URI>()
            manager.collectTransitiveIncludes("Primary", compiler, "file:///primary.cql", librarySourceMap)

            assertEquals(resolvedUri, librarySourceMap["CQMCommon"])
        }

        @Test
        fun `library already in librarySourceMap skips contentService lookup`() {
            val compiler = mock(org.cqframework.cql.cql2elm.CqlCompiler::class.java)
            val libraryManager = mock(org.cqframework.cql.cql2elm.LibraryManager::class.java)
            val libId = org.hl7.elm.r1.VersionedIdentifier().also { it.id = "ExistingLib" }
            `when`(compiler.libraryManager).thenReturn(libraryManager)
            `when`(libraryManager.compiledLibraries).thenReturn(
                linkedMapOf(libId to mock(org.cqframework.cql.cql2elm.model.CompiledLibrary::class.java)),
            )

            val librarySourceMap = mutableMapOf("ExistingLib" to URI.create("file:///existing.cql"))

            val result = manager.collectTransitiveIncludes("Primary", compiler, null, librarySourceMap)

            assertEquals(setOf("Primary", "ExistingLib"), result)
            Mockito.verifyNoInteractions(contentService)
        }
    }

    // -- updateBreakpointVerification ---------------------------------------

    @Nested
    inner class UpdateBreakpointVerification {
        @Test
        fun `null relevantLibraryIds returns immediately`() {
            val client = mock(IDebugProtocolClient::class.java)
            manager.updateBreakpointVerification(client, emptyMap(), emptyMap(), null)
        }

        @Test
        fun `unverified breakpoints get sent`() {
            val client = mock(IDebugProtocolClient::class.java)
            val uri = Paths.get("/src/lib.cql").toUri()
            val path = "/src/lib.cql"
            val bpIds = mapOf(path to mapOf(10 to 1, 20 to 2))
            val libMap = mapOf("Lib" to uri)

            manager.updateBreakpointVerification(client, bpIds, libMap, setOf("OtherLib"))

            verify(client, Mockito.times(2)).breakpoint(Mockito.any<BreakpointEventArguments>())
        }
    }

    // -- updateBreakpointLineVerification -----------------------------------

    @Nested
    inner class UpdateBreakpointLineVerification {
        @Test
        fun `empty breakpoints does not call client`() {
            val client = mock(IDebugProtocolClient::class.java)
            manager.updateBreakpointLineVerification(mock(), client, emptyMap())
        }
    }

    // -- applyBreakpointableLinesFilter -------------------------------------

    @Nested
    inner class ApplyBreakpointableLinesFilter {
        @Test
        fun `null sourcePath returns null`() {
            assertNull(manager.applyBreakpointableLinesFilter(null, null, mock(), emptyMap()))
        }
    }

    // -- resolveFrameLibraryId -----------------------------------------------

    @Nested
    inner class ResolveFrameLibraryId {
        @Test
        fun `null handler returns empty`() {
            assertEquals("", manager.resolveFrameLibraryId(null))
        }

        @Test
        fun `with call stack entry returns library id`() {
            val entry =
                StreamingBreakpointHandler.CallStackEntry(
                    def = mock(),
                    callSite = null,
                    libraryId = "MyLib",
                )
            val handler =
                mock(StreamingBreakpointHandler::class.java).also {
                    `when`(it.lastPausedCallStack).thenReturn(listOf(entry))
                }
            assertEquals("MyLib", manager.resolveFrameLibraryId(handler))
        }

        @Test
        fun `with multiple call stack entries returns the innermost (last-pushed) library id`() {
            // lastPausedCallStack is stored outermost-first (push order), so the currently-executing
            // frame is the LAST entry, not the first.
            val outer = StreamingBreakpointHandler.CallStackEntry(def = mock(), callSite = null, libraryId = "OuterLib")
            val inner = StreamingBreakpointHandler.CallStackEntry(def = mock(), callSite = null, libraryId = "InnerLib")
            val handler =
                mock(StreamingBreakpointHandler::class.java).also {
                    `when`(it.lastPausedCallStack).thenReturn(listOf(outer, inner))
                }
            assertEquals("InnerLib", manager.resolveFrameLibraryId(handler))
        }

        @Test
        fun `empty call stack falls back to primaryLibraryId`() {
            val handler =
                mock(StreamingBreakpointHandler::class.java).also {
                    `when`(it.lastPausedCallStack).thenReturn(emptyList())
                    `when`(it.primaryLibraryId).thenReturn("PrimaryLib")
                }
            assertEquals("PrimaryLib", manager.resolveFrameLibraryId(handler))
        }
    }
}
