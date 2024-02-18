package org.opencds.cqf.cql.ls.server.visitor;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.cqframework.cql.elm.tracking.TrackBack;
import org.hl7.elm.r1.Element;
import org.hl7.elm.r1.ExpressionDef;
import org.hl7.elm.r1.Library;
import org.hl7.elm.r1.Retrieve;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager;
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager;
import org.opencds.cqf.cql.ls.server.manager.IgContextManager;
import org.opencds.cqf.cql.ls.server.service.TestContentService;

class ExpressionTrackBackVisitorTest {
    private static Library library;

    @BeforeAll
    static void beforeAll() {
        ContentService cs = new TestContentService();
        CqlCompilationManager cqlCompilationManager =
                new CqlCompilationManager(cs, new CompilerOptionsManager(cs), new IgContextManager(cs));
        library = cqlCompilationManager
                .translate(
                        Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/visitor/ExpressionTrackBackVisitorTest.cql"))
                .getCompiledLibrary()
                .getLibrary();
    }

    @Test
    void positionInRetrieve_returnsRetrieve() {
        ExpressionTrackBackVisitor visitor = new ExpressionTrackBackVisitor();
        TrackBack tb = new TrackBack(library.getIdentifier(), 9, 9, 9, 9);
        Element e = visitor.visitLibrary(library, tb);
        assertNotNull(e);
        assertThat(e, instanceOf(Retrieve.class));
    }

    @Test
    void positionOutsideExpression_returnsNull() {
        ExpressionTrackBackVisitor visitor = new ExpressionTrackBackVisitor();
        TrackBack tb = new TrackBack(library.getIdentifier(), 10, 0, 10, 0);
        Element e = visitor.visitLibrary(library, tb);
        assertNull(e);
    }

    @Test
    void positionInExpression_returnsExpressionDef() {
        ExpressionTrackBackVisitor visitor = new ExpressionTrackBackVisitor();
        TrackBack tb = new TrackBack(library.getIdentifier(), 15, 10, 15, 10);
        Element e = visitor.visitLibrary(library, tb);
        assertNotNull(e);
        assertThat(e, instanceOf(ExpressionDef.class));
    }
}
