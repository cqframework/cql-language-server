package org.opencds.cqf.cql.ls.server.visitor;

import org.eclipse.lsp4j.Position;
import org.hl7.elm.r1.Element;
import org.hl7.elm.r1.ExpressionRef;
import org.hl7.elm.r1.Library;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.manager.CqlTranslationManager;
import org.opencds.cqf.cql.ls.server.manager.TranslatorOptionsManager;
import org.opencds.cqf.cql.ls.server.service.TestContentService;
import org.opencds.cqf.cql.ls.server.utility.OverlappingElements;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ExpressionOverlapVisitorTest {
    private static Library library;

    @BeforeAll
    public static void beforeAll() {
        ContentService cs = new TestContentService();
        CqlTranslationManager cqlTranslationManager =
                new CqlTranslationManager(cs, new TranslatorOptionsManager(cs));
        library = cqlTranslationManager.translate(Uris.parseOrNull(
                        "/org/opencds/cqf/cql/ls/server/gotodefinition/GoTo.cql"))
                .getTranslatedLibrary().getLibrary();
    }

    @Test
    public void shouldGetExpressionRefAtPos() throws Exception {

        ExpressionOverlapVisitor expressionOverlapVisitor = new ExpressionOverlapVisitor();
        Position position = new Position(14, 28);

        ExpressionOverlapVisitorContext context = new ExpressionOverlapVisitorContext(position);
        expressionOverlapVisitor.visitLibrary(library, context);

        List<Element> overlappingElements = context.getOverlappingElements();
        assertEquals(3, overlappingElements.size());

        Element specificElement = OverlappingElements.getMostSpecificElement(overlappingElements);
        assertTrue(specificElement instanceof ExpressionRef);

        ExpressionRef el = (ExpressionRef) specificElement;
        assertTrue(el.getName().equals("Add Def"));
    }
}
