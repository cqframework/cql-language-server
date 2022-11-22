package org.opencds.cqf.cql.ls.server.provider;

import org.hl7.elm.r1.FunctionDef;
import org.hl7.elm.r1.FunctionRef;
import org.hl7.elm.r1.Library;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.manager.CqlTranslationManager;
import org.opencds.cqf.cql.ls.server.manager.TranslatorOptionsManager;
import org.opencds.cqf.cql.ls.server.service.TestContentService;

import static org.junit.jupiter.api.Assertions.*;

public class GoToDefinitionProviderTest {

    private static Library goToLibrary;
    private static Library otherFileLibrary;

    private static FindFunctionRefVisitor findFunctionRefVisitor = new FindFunctionRefVisitor();

    @BeforeAll
    public static void beforeAll() {
        ContentService cs = new TestContentService();
        CqlTranslationManager cqlTranslationManager =
                new CqlTranslationManager(cs, new TranslatorOptionsManager(cs));

        goToLibrary = cqlTranslationManager.translate(Uris.parseOrNull(
                        "/org/opencds/cqf/cql/ls/server/gotodefinition/GoTo.cql"))
                .getTranslatedLibrary().getLibrary();

        otherFileLibrary = cqlTranslationManager.translate(Uris.parseOrNull(
                        "/org/opencds/cqf/cql/ls/server/gotodefinition/OtherFile.cql"))
                .getTranslatedLibrary().getLibrary();
    }

//    @Test
//    public void hoverInt() throws Exception {
//
//        assertNotNull(library);

//        assertEquals(2, 3);
//        Hover hover = hoverProvider.hover(new HoverParams(
//                new TextDocumentIdentifier("/org/opencds/cqf/cql/ls/server/Two.cql"),
//                new Position(5, 2)));
//
//        assertNotNull(hover);
//        assertNotNull(hover.getContents().getRight());
//
//        MarkupContent markup = hover.getContents().getRight();
//        assertEquals("markdown", markup.getKind());
//        assertEquals("```cql\nSystem.Integer\n```", markup.getValue());
//    }


    @Test void findMatchingFunctionRefForInt () {
        FunctionRef foundRef = findFunctionRefVisitor.visitLibrary(goToLibrary, "Int Func");
        assertNotNull(foundRef);

        FunctionDef foundDef = GoToDefinitionProvider.findMatchingFunctionDefInLibrary(goToLibrary.getStatements().getDef(), foundRef);
        assertNotNull(foundDef);
    }

    @Test void findMatchingFunctionRefForValueSet () {
        FunctionRef foundRef = findFunctionRefVisitor.visitLibrary(goToLibrary, "VS Func");
        assertNotNull(foundRef);

        FunctionDef foundDef = GoToDefinitionProvider.findMatchingFunctionDefInLibrary(goToLibrary.getStatements().getDef(), foundRef);
        assertNotNull(foundDef);
    }


    @Test void findMatchingFunctionWithDateTimeCast () {
        FunctionRef foundRef = findFunctionRefVisitor.visitLibrary(goToLibrary, "With Date Time");
        assertNotNull(foundRef);

        FunctionDef foundDef = GoToDefinitionProvider.findMatchingFunctionDefInLibrary(goToLibrary.getStatements().getDef(), foundRef);
        assertNotNull(foundDef);
    }

}

