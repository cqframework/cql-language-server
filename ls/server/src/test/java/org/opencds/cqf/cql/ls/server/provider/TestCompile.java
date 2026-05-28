// Run this via Maven by adding to test sources
package org.opencds.cqf.cql.ls.server.provider;

import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager;
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager;
import org.opencds.cqf.cql.ls.server.manager.IgContextManager;
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager;
import org.opencds.cqf.cql.ls.server.service.TestContentService;
import org.hl7.elm.r1.Query;

public class TestCompile {
    public static void main(String[] args) {
        var cs = new TestContentService();
        var cm = new CqlCompilationManager(cs, new CompilerOptionsManager(cs), new IgContextManager(cs), new LibraryResolutionManager(java.util.Collections.emptyList()));
        var uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/ReturnClauseQuery.cql");
        var compiler = cm.compile(uri);
        if (compiler == null) { System.out.println("COMPILER NULL"); return; }
        if (compiler.getExceptions() != null) {
            System.out.println("EXCEPTIONS: " + compiler.getExceptions().size());
            for (var e : compiler.getExceptions()) {
                System.out.println("  " + (e != null ? e.getMessage() : "null"));
            }
        } else {
            System.out.println("NO EXCEPTIONS");
        }
        var lib = compiler.getLibrary();
        if (lib == null) { System.out.println("LIBRARY NULL"); return; }
        for (var def : lib.getStatements().getDef()) {
            System.out.println("Def: " + def.getName() + " -> " + (def.getExpression() != null ? def.getExpression().getClass().getName() : "NULL"));
            if (def.getExpression() instanceof Query) {
                var q = (Query) def.getExpression();
                System.out.println("  return=" + (q.getReturn() != null));
                if (q.getReturn() != null) {
                    System.out.println("  return.loc=" + q.getReturn().getLocator());
                    System.out.println("  return.expr=" + (q.getReturn().getExpression() != null ? q.getReturn().getExpression().getClass().getName() : "null"));
                }
                System.out.println("  where=" + (q.getWhere() != null));
                System.out.println("  let=" + (q.getLet() != null ? q.getLet().size() : -1));
                System.out.println("  sort=" + (q.getSort() != null));
            }
        }
    }
}
