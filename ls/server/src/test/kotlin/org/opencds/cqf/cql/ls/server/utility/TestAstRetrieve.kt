package org.opencds.cqf.cql.ls.server.utility

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService

class TestAstRetrieve {
    @Test
    fun dumpWithFhirQueryAst() {
        val cs = TestContentService()
        val cm = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithFhirQuery.cql")!!
        val compiler = cm.compile(uri) ?: throw AssertionError("compile returned null")
        val result = ElmAstLibraryWriter(compiler).writeAsString(compiler.library!!)
        println("=== AST OUTPUT ===")
        println(result)
        println("=== END ===")
        assertTrue(result.contains("dataType:"), "Expected dataType")
    }
}
