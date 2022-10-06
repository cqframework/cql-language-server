package org.opencds.cqf.cql.ls.server.provider;

import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.opencds.cqf.cql.ls.server.manager.CqlTranslationManager;

public class CompletionProvider {

    private CqlTranslationManager cqlTranslationManager;

    public CompletionProvider(CqlTranslationManager cqlTranslationManager) {
        this.cqlTranslationManager = cqlTranslationManager;
    }

    public Either<List<CompletionItem>, CompletionList> completion(
            CompletionParams completionParams) {
        return null;
    }

}
