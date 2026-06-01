package org.opencds.cqf.cql.ls.server.utility

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService

class ElmAstLibraryWriterTest {
    companion object {
        private lateinit var compilationManager: CqlCompilationManager

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val cs = TestContentService()
            compilationManager =
                CqlCompilationManager(
                    cs,
                    CompilerOptionsManager(cs),
                    IgContextManager(cs),
                    LibraryResolutionManager(emptyList()),
                )
        }
    }

    @Test
    fun writeAsString_withCompiler_showsReturnType() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        val compiler = compilationManager.compile(uri) ?: throw AssertionError("compile returned null")
        val result = ElmAstLibraryWriter(compiler).writeAsString(compiler.library!!)

        assertTrue(result.contains("returns System.Integer"), "Expected return type in AST output: $result")
    }

    @Test
    fun writeAsString_withCompiler_containsDefineAndLiteral() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/One.cql")!!
        val compiler = compilationManager.compile(uri) ?: throw AssertionError("compile returned null")
        val result = ElmAstLibraryWriter(compiler).writeAsString(compiler.library!!)

        assertTrue(result.contains("define:"), "Expected define in AST output: $result")
        assertTrue(result.contains("Literal:"), "Expected Literal in AST output: $result")
    }

    @Test
    fun writeAsString_withFhirQuery_containsRetrieveWithDataType() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithFhirQuery.cql")!!
        val compiler =
            compilationManager.compile(uri)
                ?: throw AssertionError("compile returned null for WithFhirQuery")
        val result = ElmAstLibraryWriter(compiler).writeAsString(compiler.library!!)

        assertTrue(result.contains("Retrieve"), "Expected Retrieve in AST output: $result")
        assertTrue(result.contains("dataType:"), "Expected dataType in AST output: $result")
        assertTrue(result.contains("codeProperty:"), "Expected codeProperty in AST output: $result")
        assertTrue(result.contains("codeComparator:"), "Expected codeComparator in AST output: $result")
        assertTrue(result.contains("codes:"), "Expected codes in AST output: $result")
    }
}
