package org.opencds.cqf.cql.ls.server.provider;

import org.junit.jupiter.api.BeforeAll;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.server.manager.CqlTranslationManager;
import org.opencds.cqf.cql.ls.server.manager.TranslatorOptionsManager;
import org.opencds.cqf.cql.ls.server.service.TestContentService;

public class CompletionProviderTest {

    private static CompletionProvider completionProvider;

    @BeforeAll
    public static void beforeAll() {
        ContentService cs = new TestContentService();
        CqlTranslationManager cqlTranslationManager =
                new CqlTranslationManager(cs, new TranslatorOptionsManager(cs));
        completionProvider = new CompletionProvider(cqlTranslationManager);
    }

}
