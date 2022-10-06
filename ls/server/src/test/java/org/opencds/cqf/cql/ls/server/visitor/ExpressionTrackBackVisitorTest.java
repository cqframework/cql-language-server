package org.opencds.cqf.cql.ls.server.visitor;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.eclipse.lsp4j.Position;
import org.hl7.elm.r1.Element;
import org.hl7.elm.r1.ExpressionDef;
import org.hl7.elm.r1.ExpressionRef;
import org.hl7.elm.r1.Library;
import org.hl7.elm.r1.Retrieve;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.manager.CqlTranslationManager;
import org.opencds.cqf.cql.ls.server.manager.TranslatorOptionsManager;
import org.opencds.cqf.cql.ls.server.service.TestContentService;

public class ExpressionTrackBackVisitorTest {
    private static Library library;

    @BeforeAll
    public static void beforeAll() {
        ContentService cs = new TestContentService();
        CqlTranslationManager cqlTranslationManager =
                new CqlTranslationManager(cs, new TranslatorOptionsManager(cs));
        library = cqlTranslationManager.translate(Uris.parseOrNull(
                "/org/opencds/cqf/cql/ls/server/visitor/ExpressionTrackBackVisitorTest.cql"))
                .getTranslatedLibrary().getLibrary();
    }

    @Test
    public void positionInRetrieve_returnsRetrieve() {
        ExpressionTrackBackVisitor visitor = new ExpressionTrackBackVisitor();
        Position p = new Position(8, 9);
        Element e = visitor.visitLibrary(library, p);
        assertNotNull(e);
        assertThat(e, instanceOf(Retrieve.class));
    }

    @Test
    public void positionOutsideExpression_returnsNull() {
        ExpressionTrackBackVisitor visitor = new ExpressionTrackBackVisitor();
        Position p = new Position(9, 0);
        Element e = visitor.visitLibrary(library, p);
        assertNull(e);
    }

    @Test
    public void positionInExpression_returnsExpressionDef() {
        ExpressionTrackBackVisitor visitor = new ExpressionTrackBackVisitor();
        Position p = new Position(13, 10);
        Element e = visitor.visitLibrary(library, p);
        assertNotNull(e);
        assertThat(e, instanceOf(ExpressionDef.class));
    }

    @Test
    public void positionInExpressionDef_returnsExpressionRef() {
        ExpressionTrackBackVisitor visitor = new ExpressionTrackBackVisitor();
        Position p = new Position(14, 10);
        Element e = visitor.visitLibrary(library, p);
        assertNotNull(e);
        assertThat(e, instanceOf(ExpressionRef.class));
    }
}
