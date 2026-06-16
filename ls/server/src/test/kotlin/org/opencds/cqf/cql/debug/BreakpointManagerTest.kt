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
        fun `compiler with null compiledLibrary returns just primary`() {
            val compiler = mock(org.cqframework.cql.cql2elm.CqlCompiler::class.java)
            `when`(compiler.compiledLibrary).thenReturn(null)

            val result = manager.collectTransitiveIncludes("Primary", compiler, null, mutableMapOf())
            assertEquals(setOf("Primary"), result)
        }

        @Test
        fun `compiler with null library returns just primary`() {
            val compiler = mock(org.cqframework.cql.cql2elm.CqlCompiler::class.java)
            val compiledLib = mock(org.cqframework.cql.cql2elm.model.CompiledLibrary::class.java)
            `when`(compiler.compiledLibrary).thenReturn(compiledLib)
            `when`(compiledLib.library).thenReturn(null)

            val result = manager.collectTransitiveIncludes("Primary", compiler, null, mutableMapOf())
            assertEquals(setOf("Primary"), result)
        }

        @Test
        fun `includeDef with null path is skipped`() {
            val compiler = mock(org.cqframework.cql.cql2elm.CqlCompiler::class.java)
            val compiledLib = mock(org.cqframework.cql.cql2elm.model.CompiledLibrary::class.java)
            val library = mock(org.hl7.elm.r1.Library::class.java)
            val includes = mock(org.hl7.elm.r1.Library.Includes::class.java)
            val includeDef = mock(org.hl7.elm.r1.IncludeDef::class.java)
            `when`(compiler.compiledLibrary).thenReturn(compiledLib)
            `when`(compiledLib.library).thenReturn(library)
            `when`(library.includes).thenReturn(includes)
            `when`(includes.def).thenReturn(mutableListOf(includeDef))
            `when`(includeDef.path).thenReturn(null)

            val result = manager.collectTransitiveIncludes("Primary", compiler, null, mutableMapOf())
            assertEquals(setOf("Primary"), result)
        }

        @Test
        fun `duplicate library in includes is deduped`() {
            val compiler = mock(org.cqframework.cql.cql2elm.CqlCompiler::class.java)
            val compiledLib = mock(org.cqframework.cql.cql2elm.model.CompiledLibrary::class.java)
            val library = mock(org.hl7.elm.r1.Library::class.java)
            val includes = mock(org.hl7.elm.r1.Library.Includes::class.java)
            val includeDef1 = mock(org.hl7.elm.r1.IncludeDef::class.java)
            val includeDef2 = mock(org.hl7.elm.r1.IncludeDef::class.java)
            `when`(compiler.compiledLibrary).thenReturn(compiledLib)
            `when`(compiledLib.library).thenReturn(library)
            `when`(library.includes).thenReturn(includes)
            `when`(includes.def).thenReturn(mutableListOf(includeDef1, includeDef2))
            `when`(includeDef1.path).thenReturn("DuplicateLib")
            `when`(includeDef2.path).thenReturn("DuplicateLib")

            val result = manager.collectTransitiveIncludes("Primary", compiler, null, mutableMapOf())
            assertEquals(setOf("Primary", "DuplicateLib"), result)
        }

        @Test
        fun `library already in librarySourceMap skips contentService lookup`() {
            val compiler = mock(org.cqframework.cql.cql2elm.CqlCompiler::class.java)
            val compiledLib = mock(org.cqframework.cql.cql2elm.model.CompiledLibrary::class.java)
            val library = mock(org.hl7.elm.r1.Library::class.java)
            val includes = mock(org.hl7.elm.r1.Library.Includes::class.java)
            val includeDef = mock(org.hl7.elm.r1.IncludeDef::class.java)
            `when`(compiler.compiledLibrary).thenReturn(compiledLib)
            `when`(compiledLib.library).thenReturn(library)
            `when`(library.includes).thenReturn(includes)
            `when`(includes.def).thenReturn(mutableListOf(includeDef))
            `when`(includeDef.path).thenReturn("ExistingLib")
            `when`(includeDef.version).thenReturn(null)

            val librarySourceMap = mutableMapOf("ExistingLib" to URI.create("file:///existing.cql"))

            val result = manager.collectTransitiveIncludes("Primary", compiler, null, librarySourceMap)

            assertEquals(setOf("Primary", "ExistingLib"), result)
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
