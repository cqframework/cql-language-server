package org.opencds.cqf.cql.ls.server.manager

import org.cqframework.cql.cql2elm.CqlCompilerException
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.service.TestContentService
import java.io.InputStream
import java.net.URI

class CqlCompilationManagerTest {
    companion object {
        private val cs = TestContentService()
        private lateinit var manager: CqlCompilationManager

        private val ONE_URI: URI = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        private val TWO_URI: URI = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/Two.cql")!!
        private val SYNTAX_ERROR_URI: URI = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/SyntaxError.cql")!!
        private val MISSING_INCLUDE_URI: URI = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/MissingInclude.cql")!!
        private val FUNCTION_LIB_URI: URI = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionLib.cql")!!
        private val FUNCTION_CALLER_URI: URI = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            manager = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
        }
    }

    // -----------------------------------------------------------------------
    // compile(URI) — null when content service has no content for the URI
    // -----------------------------------------------------------------------

    @Test
    fun compile_uri_returnsNull_whenContentServiceReturnsNull() {
        val nullCs =
            object : ContentService {
                override fun locate(
                    root: URI,
                    libraryIdentifier: VersionedIdentifier,
                ): Set<URI> = emptySet()

                override fun read(uri: URI): InputStream? = null
            }
        val localManager = CqlCompilationManager(nullCs, CompilerOptionsManager(nullCs), IgContextManager(nullCs), LibraryResolutionManager(emptyList()))
        val result = localManager.compile(URI("file:///nonexistent.cql"))
        assertNull(result)
    }

    // -----------------------------------------------------------------------
    // compile(URI) — valid CQL produces a compiler with no errors
    // -----------------------------------------------------------------------

    @Test
    fun compile_validCql_returnsNonNull() {
        val compiler = manager.compile(ONE_URI)
        assertNotNull(compiler)
    }

    @Test
    fun compile_validCql_hasNoErrors() {
        val compiler = manager.compile(ONE_URI)!!
        val errors =
            compiler.exceptions.filter {
                it.severity == CqlCompilerException.ErrorSeverity.Error
            }
        assertTrue(errors.isEmpty(), "Expected no errors for One.cql")
    }

    @Test
    fun compile_validCqlWithInclude_hasNoErrors() {
        val compiler = manager.compile(TWO_URI)!!
        val errors =
            compiler.exceptions.filter {
                it.severity == CqlCompilerException.ErrorSeverity.Error
            }
        assertTrue(errors.isEmpty(), "Expected no errors for Two.cql (which includes One.cql)")
    }

    // -----------------------------------------------------------------------
    // compile(URI) — invalid CQL produces errors
    // -----------------------------------------------------------------------

    @Test
    fun compile_syntaxError_returnsNonNull() {
        val compiler = manager.compile(SYNTAX_ERROR_URI)
        assertNotNull(compiler)
    }

    @Test
    fun compile_syntaxError_hasErrors() {
        val compiler = manager.compile(SYNTAX_ERROR_URI)!!
        val errors =
            compiler.exceptions.filter {
                it.severity == CqlCompilerException.ErrorSeverity.Error
            }
        assertFalse(errors.isEmpty(), "Expected errors for CQL with syntax errors")
    }

    @Test
    fun compile_missingInclude_hasErrors() {
        val compiler = manager.compile(MISSING_INCLUDE_URI)!!
        assertFalse(
            compiler.exceptions.isEmpty(),
            "Expected exceptions for CQL with a missing include",
        )
    }

    // -----------------------------------------------------------------------
    // compile(URI, InputStream) — accepts a stream directly (bypasses read())
    // -----------------------------------------------------------------------

    @Test
    fun compile_stream_returnsNonNull() {
        val stream = cs.read(ONE_URI)!!
        val compiler = manager.compile(ONE_URI, stream)
        assertNotNull(compiler)
    }

    @Test
    fun compile_stream_validCql_hasNoErrors() {
        val stream = cs.read(ONE_URI)!!
        val compiler = manager.compile(ONE_URI, stream)
        val errors =
            compiler.exceptions.filter {
                it.severity == CqlCompilerException.ErrorSeverity.Error
            }
        assertTrue(errors.isEmpty(), "Expected no errors when compiling One.cql from stream")
    }

    // -----------------------------------------------------------------------
    // Compilation cache — cache hits and surgical invalidation
    // Each test uses its own manager instance to avoid inter-test cache bleed.
    // -----------------------------------------------------------------------

    @Test
    fun compile_cachedResult_returnsSameInstance() {
        val localManager = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
        val first = localManager.compile(ONE_URI)
        val second = localManager.compile(ONE_URI)
        assertSame(first, second, "Expected cache hit to return the same CqlCompiler instance")
    }

    @Test
    fun invalidate_evictsChangedFile() {
        val localManager = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
        val first = localManager.compile(ONE_URI)
        localManager.invalidate(ONE_URI)
        val second = localManager.compile(ONE_URI)
        assertNotSame(first, second, "Expected a new compiler instance after invalidation")
    }

    @Test
    fun invalidate_evictsDependentFile_butNotUnrelated() {
        val localManager = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
        localManager.compile(FUNCTION_LIB_URI)
        val caller = localManager.compile(FUNCTION_CALLER_URI)
        val unrelated = localManager.compile(ONE_URI)

        localManager.invalidate(FUNCTION_LIB_URI)

        // FunctionCaller depends on FunctionLib → evicted → new instance
        val callerAfter = localManager.compile(FUNCTION_CALLER_URI)
        assertNotSame(caller, callerAfter, "Dependent file should be evicted when its dependency is invalidated")

        // One.cql is unrelated → still cached → same instance
        val unrelatedAfter = localManager.compile(ONE_URI)
        assertSame(unrelated, unrelatedAfter, "Unrelated file should remain in cache")
    }

    @Test
    fun compile_stream_populatesCache() {
        val localManager = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
        val stream = cs.read(ONE_URI)!!
        val fromStream = localManager.compile(ONE_URI, stream)
        val fromCache = localManager.compile(ONE_URI)
        assertSame(fromStream, fromCache, "compile(uri) should return the instance cached by compile(uri, stream)")
    }

    @Test
    fun invalidate_onUncachedUri_isNoOp() {
        val localManager = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
        // Should not throw — URI was never compiled
        localManager.invalidate(ONE_URI)
        // Cache miss → fresh compile
        assertNotNull(localManager.compile(ONE_URI))
    }

    @Test
    fun getDependentUris_returnsCallers() {
        val localManager = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))

        val libIdentifier = localManager.compile(FUNCTION_LIB_URI)!!.compiledLibrary!!.library!!.identifier!!
        localManager.compile(FUNCTION_CALLER_URI)

        val deps = localManager.getDependentUris(libIdentifier)

        assertTrue(FUNCTION_CALLER_URI in deps, "FunctionCaller should be listed as a dependent of FunctionLib")
        assertFalse(ONE_URI in deps, "One.cql should not appear as a dependent of FunctionLib")
    }
}
