package org.opencds.cqf.cql.ls.server.provider;
import org.cqframework.cql.elm.tracking.Trackable;
import org.cqframework.cql.elm.visiting.ElmBaseLibraryVisitor;
import org.hl7.elm.r1.FunctionRef;

public class FindFunctionRefVisitor extends ElmBaseLibraryVisitor<FunctionRef, String> {

    @Override
    protected FunctionRef aggregateResult(FunctionRef aggregate, FunctionRef nextResult) {
       if (nextResult != null) {
           return nextResult;
       }
        if (aggregate != null) {
            return aggregate;
        }
        return super.aggregateResult(aggregate, nextResult);
    }

    @Override
    protected FunctionRef defaultResult(Trackable elm, String searchName) {
        if (elm instanceof FunctionRef) {
            FunctionRef functionRef = (FunctionRef) elm;
            if (functionRef.getName().equals(searchName)) {
                return functionRef;
            }
        }

        return super.defaultResult(elm, searchName);
    }
}
