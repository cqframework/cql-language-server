package org.opencds.cqf.cql.ls.visitor;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.CoreMatchers.instanceOf;

import org.cqframework.cql.elm.tracking.TrackBack;
import org.hl7.elm.r1.Element;
import org.hl7.elm.r1.ExpressionDef;
import org.hl7.elm.r1.Library;
import org.hl7.elm.r1.Retrieve;
import org.opencds.cqf.cql.ls.TranslatingTestBase;
import org.testng.annotations.Test;


public class ExpressionTrackBackVisitorTest extends TranslatingTestBase {

    @Test
    public void positionInRetrieve_returnsRetrieve() {
        ExpressionTrackBackVisitor visitor = new ExpressionTrackBackVisitor();
        Library library = this.cqlTranslator.getTranslatedLibrary().getLibrary();
        TrackBack tb = new TrackBack(library.getIdentifier(), 9, 9, 9, 9);
        Element e = visitor.visitLibrary(this.cqlTranslator.getTranslatedLibrary().getLibrary(), tb);
        assertNotNull(e);
        assertThat(e, instanceOf(Retrieve.class));
    }

    @Test
    public void positionOutsideExpression_returnsNull() {
        ExpressionTrackBackVisitor visitor = new ExpressionTrackBackVisitor();
        Library library = this.cqlTranslator.getTranslatedLibrary().getLibrary();
        TrackBack tb = new TrackBack(library.getIdentifier(), 10, 0, 10, 0);
        Element e = visitor.visitLibrary(this.cqlTranslator.getTranslatedLibrary().getLibrary(), tb);
        assertNull(e);
    }

    @Test
    public void positionInExpression_returnsExpressionDef() {
        ExpressionTrackBackVisitor visitor = new ExpressionTrackBackVisitor();
        Library library = this.cqlTranslator.getTranslatedLibrary().getLibrary();
        TrackBack tb = new TrackBack(library.getIdentifier(), 15, 10, 15, 10);
        Element e = visitor.visitLibrary(this.cqlTranslator.getTranslatedLibrary().getLibrary(), tb);
        assertNotNull(e);
        assertThat(e, instanceOf(ExpressionDef.class));
    }
}
