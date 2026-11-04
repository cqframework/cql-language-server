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
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService
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

    private fun compilationManager(): CqlCompilationManager {
        val cs = TestContentService()
        return CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
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

        @Test
        fun `virtual path in minted triple-slash form returns lib id`() {
            val map = mapOf("FHIRCommon" to URI.create("cql-virtual:///FHIRCommon-2.0.0.cql"))
            assertEquals("FHIRCommon", manager.resolveLibraryIdFromPath("cql-virtual:///FHIRCommon-2.0.0.cql", map))
        }

        @Test
        fun `virtual path in VS Code single-slash form returns lib id`() {
            // VS Code's Uri.toString() drops the empty authority, so breakpoints set in a
            // cql-virtual tab come back as cql-virtual:/X.cql.
            val map = mapOf("FHIRCommon" to URI.create("cql-virtual:///FHIRCommon-2.0.0.cql"))
            assertEquals("FHIRCommon", manager.resolveLibraryIdFromPath("cql-virtual:/FHIRCommon-2.0.0.cql", map))
        }

        @Test
        fun `unknown virtual path returns null`() {
            val map = mapOf("FHIRCommon" to URI.create("cql-virtual:///FHIRCommon-2.0.0.cql"))
            assertNull(manager.resolveLibraryIdFromPath("cql-virtual:///FHIRCommon-3.0.0.cql", map))
        }

        @Test
        fun `virtual path never matches a file entry`() {
            val map = mapOf("TestLib" to Paths.get("/test/path.cql").toUri())
            assertNull(manager.resolveLibraryIdFromPath("cql-virtual:///TestLib-1.0.0.cql", map))
        }
    }

    // -- sourcePathMatches ---------------------------------------------------

    // | Source.path                         | Library URI                              | Match |
    // | ----------------------------------- | ---------------------------------------- | ----- |
    // | /a/b/Lib.cql                        | file:///a/b/Lib.cql                      | yes   |
    // | C:\a\b\Lib.cql, C:/a/b/Lib.cql      | cql-virtual:///Lib-1.0.0.cql             | no    |
    // | cql-virtual:/Lib-1.0.0.cql          | cql-virtual:///Lib-1.0.0.cql             | yes   |
    // | cql-virtual:///Lib-1.0.0.cql        | file:///a/b/Lib.cql                      | no    |
    // Windows drive-letter paths matching file URIs are covered by the @EnabledOnOs(WINDOWS)
    // tests above (Paths.get on a drive-letter path is only meaningful on Windows).
    @Nested
    inner class SourcePathMatches {
        private val virtualUri = URI.create("cql-virtual:///Lib-1.0.0.cql")

        @Test
        fun `posix path matches its file URI`() {
            assertTrue(manager.sourcePathMatches("/a/b/Lib.cql", Paths.get("/a/b/Lib.cql").toUri()))
        }

        @Test
        fun `drive-less file URI matches its Paths-derived path`() {
            val uri = URI.create("file:///a/b/Lib.cql")
            assertTrue(manager.sourcePathMatches(Paths.get(uri).toString(), uri))
        }

        @Test
        fun `windows backslash path never matches a virtual URI`() {
            assertFalse(manager.sourcePathMatches("C:\\a\\b\\Lib.cql", virtualUri))
        }

        @Test
        fun `windows forward slash path never matches a virtual URI`() {
            assertFalse(manager.sourcePathMatches("C:/a/b/Lib.cql", virtualUri))
        }

        @Test
        fun `both virtual URI forms match`() {
            assertTrue(manager.sourcePathMatches("cql-virtual:///Lib-1.0.0.cql", virtualUri))
            assertTrue(manager.sourcePathMatches("cql-virtual:/Lib-1.0.0.cql", virtualUri))
        }

        @Test
        fun `virtual path never matches a file URI`() {
            assertFalse(manager.sourcePathMatches("cql-virtual:///Lib-1.0.0.cql", Paths.get("/a/b/Lib.cql").toUri()))
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
            val source = manager.resolveSource("TestLib", null, null, map)
            assertTrue(source.path.endsWith("test.cql"))
        }

        @Test
        fun `unsupported non-file URI falls back to a named placeholder`() {
            val uri = URI.create("https://example.com/lib.cql")
            val map = mapOf("TestLib" to uri)
            val source = manager.resolveSource("TestLib", null, null, map)
            assertNull(source.path)
            assertEquals(0, source.sourceReference)
            assertEquals("TestLib.cql", source.name)
        }

        @Test
        fun `virtual URI path is the cql-virtual URI, no sourceReference, name includes version`() {
            val uri = URI.create("cql-virtual:///FHIRCommon-2.0.0.cql")
            val map = mapOf("FHIRCommon" to uri)
            val source = manager.resolveSource("FHIRCommon", null, null, map)
            assertEquals("cql-virtual:///FHIRCommon-2.0.0.cql", source.path)
            assertNull(source.sourceReference)
            assertEquals("FHIRCommon-2.0.0.cql", source.name)
        }

        @Test
        fun `unversioned virtual URI`() {
            val uri = URI.create("cql-virtual:///X-unversioned.cql")
            val map = mapOf("X" to uri)
            val source = manager.resolveSource("X", null, null, map)
            assertEquals("cql-virtual:///X-unversioned.cql", source.path)
            assertNull(source.sourceReference)
            assertEquals("X-unversioned.cql", source.name)
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
                )
            assertTrue(source.path.endsWith("streaming.cql"))
        }

        @Test
        fun `unknown library returns sourceReference 0`() {
            val source = manager.resolveSource("Unknown", null, null, emptyMap())
            assertEquals(0, source.sourceReference)
        }

        @Test
        fun `unresolved library still gets a legible source name, not blank`() {
            val source = manager.resolveSource("CQMCommon", null, null, emptyMap())
            assertEquals("CQMCommon.cql", source.name)
        }

        @Test
        fun `empty library id uses streaming fallback`() {
            val handler =
                mock(StreamingBreakpointHandler::class.java).also {
                    `when`(it.primaryLibraryId).thenReturn("PrimaryLib")
                }
            val source =
                manager.resolveSource("", null, handler, emptyMap())
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

        @Test
        fun `library with no workspace file is mapped to its cql-virtual URI`() {
            val compiler = mock(org.cqframework.cql.cql2elm.CqlCompiler::class.java)
            val libraryManager = mock(org.cqframework.cql.cql2elm.LibraryManager::class.java)
            val libId =
                org.hl7.elm.r1.VersionedIdentifier().also {
                    it.id = "FHIRCommon"
                    it.version = "2.0.0"
                }
            `when`(compiler.libraryManager).thenReturn(libraryManager)
            `when`(libraryManager.compiledLibraries).thenReturn(
                linkedMapOf(libId to mock(org.cqframework.cql.cql2elm.model.CompiledLibrary::class.java)),
            )
            val cm = compilationManager()
            val virtualUri = URI.create("cql-virtual:///FHIRCommon-2.0.0.cql")
            cm.compile(virtualUri, "library FHIRCommon version '2.0.0'\ndefine \"A\": 1".byteInputStream())

            val librarySourceMap = mutableMapOf<String, URI>()
            manager.collectTransitiveIncludes("Primary", compiler, "file:///primary.cql", librarySourceMap, cm)

            assertEquals(virtualUri, librarySourceMap["FHIRCommon"])
        }

        @Test
        fun `library with no workspace file and no resolvable source stays unmapped`() {
            val compiler = mock(org.cqframework.cql.cql2elm.CqlCompiler::class.java)
            val libraryManager = mock(org.cqframework.cql.cql2elm.LibraryManager::class.java)
            val libId =
                org.hl7.elm.r1.VersionedIdentifier().also {
                    it.id = "NoSuchLibrary"
                    it.version = "9.9.9"
                }
            `when`(compiler.libraryManager).thenReturn(libraryManager)
            `when`(libraryManager.compiledLibraries).thenReturn(
                linkedMapOf(libId to mock(org.cqframework.cql.cql2elm.model.CompiledLibrary::class.java)),
            )

            val librarySourceMap = mutableMapOf<String, URI>()
            val result =
                manager.collectTransitiveIncludes("Primary", compiler, "file:///primary.cql", librarySourceMap, compilationManager())

            assertEquals(setOf("Primary", "NoSuchLibrary"), result)
            assertNull(librarySourceMap["NoSuchLibrary"])
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
            manager.updateBreakpointLineVerification(client, emptyMap(), emptyMap(), mock())
            Mockito.verifyNoInteractions(client)
        }

        @Test
        fun `each path is checked against its own library's lines, not the primary's`() {
            val cm = compilationManager()
            // Primary: line 2 is breakpointable. Included library: line 2 is blank, line 3 is not.
            val primaryUri = URI.create("cql-virtual:///Primary-1.0.0.cql")
            cm.compile(primaryUri, "library Primary version '1.0.0'\ndefine \"P\": 1".byteInputStream())
            val includedUri = URI.create("cql-virtual:///Included-1.0.0.cql")
            cm.compile(includedUri, "library Included version '1.0.0'\n\ndefine \"I\": 1".byteInputStream())
            val librarySourceMap = mapOf("Primary" to primaryUri, "Included" to includedUri)
            val client = mock(IDebugProtocolClient::class.java)
            val captor = org.mockito.ArgumentCaptor.forClass(BreakpointEventArguments::class.java)

            manager.updateBreakpointLineVerification(
                client,
                mapOf("cql-virtual:/Included-1.0.0.cql" to mapOf(2 to 11, 3 to 12)),
                librarySourceMap,
                cm,
            )

            verify(client, Mockito.times(1)).breakpoint(captor.capture())
            assertEquals(11, captor.value.breakpoint.id)
            assertFalse(captor.value.breakpoint.isVerified)
        }

        @Test
        fun `unmapped paths are left alone`() {
            val client = mock(IDebugProtocolClient::class.java)
            manager.updateBreakpointLineVerification(
                client,
                mapOf("cql-virtual:/Unknown-1.0.0.cql" to mapOf(1 to 1)),
                emptyMap(),
                compilationManager(),
            )
            Mockito.verifyNoInteractions(client)
        }
    }

    // -- applyBreakpointableLinesFilter -------------------------------------

    @Nested
    inner class ApplyBreakpointableLinesFilter {
        @Test
        fun `null sourcePath returns null`() {
            assertNull(manager.applyBreakpointableLinesFilter(null, null, mock(), emptyMap()))
        }

        @Test
        fun `virtual path in single-slash form uses that library's parse tree`() {
            val cm = compilationManager()
            val uri = URI.create("cql-virtual:///Included-1.0.0.cql")
            cm.compile(uri, "library Included version '1.0.0'\n\ndefine \"I\": 1".byteInputStream())

            val lines = manager.applyBreakpointableLinesFilter("cql-virtual:/Included-1.0.0.cql", null, cm, mapOf("Included" to uri))

            assertTrue(lines != null && 3 in lines && 2 !in lines)
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
