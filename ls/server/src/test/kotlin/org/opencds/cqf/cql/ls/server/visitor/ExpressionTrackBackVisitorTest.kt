package org.opencds.cqf.cql.ls.server.visitor

import org.cqframework.cql.cql2elm.tracking.TrackBack
import org.hamcrest.CoreMatchers.instanceOf
import org.hamcrest.MatcherAssert.assertThat
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.Library
import org.hl7.elm.r1.Retrieve
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.service.TestContentService

class ExpressionTrackBackVisitorTest {
    companion object {
        private lateinit var library: Library

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val cs = TestContentService()
            val cqlCompilationManager =
                CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs))
            library =
                cqlCompilationManager
                    .compile(Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/visitor/ExpressionTrackBackVisitorTest.cql")!!)!!
                    .compiledLibrary!!
                    .library!!
        }
    }

    @Test
    fun positionInRetrieve_returnsRetrieve() {
        val visitor = ExpressionTrackBackVisitor()
        val tb = TrackBack(library.identifier, 9, 9, 9, 9)
        val e = visitor.visitLibrary(library, tb)
        assertNotNull(e)
        assertThat(e, instanceOf(Retrieve::class.java))
    }

    @Test
    fun positionOutsideExpression_returnsNull() {
        val visitor = ExpressionTrackBackVisitor()
        val tb = TrackBack(library.identifier, 10, 0, 10, 0)
        val e = visitor.visitLibrary(library, tb)
        assertNull(e)
    }

    @Test
    fun positionInExpression_returnsExpressionDef() {
        val visitor = ExpressionTrackBackVisitor()
        val tb = TrackBack(library.identifier, 15, 10, 15, 10)
        val e = visitor.visitLibrary(library, tb)
        assertNotNull(e)
        assertThat(e, instanceOf(ExpressionDef::class.java))
    }

    @Test
    fun positionInExpressionRef_returnsExpressionDef() {
        // Line 12 (1-indexed): "    "ObservationRetrieve"" — the ExpressionRef inside ObservationReference
        val visitor = ExpressionTrackBackVisitor()
        val tb = TrackBack(library.identifier, 12, 5, 12, 25)
        val e = visitor.visitLibrary(library, tb)
        assertNotNull(e)
        assertThat(e, instanceOf(ExpressionDef::class.java))
    }

    @Test
    fun positionAtExpressionBoundary_returnsExpressionDef() {
        // Line 15 (1-indexed): "    Patient.birthDate" — at the start char of PropertyAccess body
        val visitor = ExpressionTrackBackVisitor()
        val tb = TrackBack(library.identifier, 15, 5, 15, 5)
        val e = visitor.visitLibrary(library, tb)
        assertNotNull(e)
        assertThat(e, instanceOf(ExpressionDef::class.java))
    }
}
