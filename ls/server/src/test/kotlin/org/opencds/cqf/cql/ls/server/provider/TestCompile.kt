package org.opencds.cqf.cql.ls.server.provider

import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService
import org.hl7.elm.r1.Query

class TestCompile {
    @Test
    fun dumpReturnClauseQueryAst() {
        val cs = TestContentService()
        val cm = CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/ReturnClauseQuery.cql")!!
        val compiler = cm.compile(uri) ?: run { println("COMPILER NULL"); return }

        val exceptions = compiler.exceptions
        if (exceptions != null) {
            println("EXCEPTIONS: " + exceptions.size)
            for (e in exceptions) {
                println("  " + (e?.message ?: "null"))
            }
        } else {
            println("NO EXCEPTIONS")
        }

        val lib = compiler.library ?: run { println("LIBRARY NULL"); return }
        val statements = lib.statements ?: run { println("STATEMENTS NULL"); return }
        for (def in statements.def) {
            println("Def: " + def.name + " -> " + (def.expression?.let { it.javaClass.name } ?: "NULL"))
            if (def.expression is Query) {
                val q = def.expression as Query
                println("  return=" + (q.`return` != null))
                q.`return`?.let {
                    println("  return.loc=" + it.locator)
                    println("  return.expr=" + (it.expression?.javaClass?.name ?: "null"))
                }
                println("  where=" + (q.where != null))
                println("  let=" + (q.let?.size ?: -1))
                println("  sort=" + (q.sort != null))
            }
        }
    }
}
