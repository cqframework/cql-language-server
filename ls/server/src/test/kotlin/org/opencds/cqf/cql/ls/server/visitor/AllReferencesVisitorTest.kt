package org.opencds.cqf.cql.ls.server.visitor

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService

class AllReferencesVisitorTest {
    companion object {
        private lateinit var compilationManager: CqlCompilationManager

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val cs = TestContentService()
            compilationManager =
                CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
        }
    }

    @Test
    fun visitExpressionRef_matchingName_returnsLocation() {
        // FunctionLib.cql: define "MyValue": 1
        // FunctionCaller.cql references FL."MyValue" — but we're searching FunctionLib itself,
        // not the caller. In FunctionLib, "MyValue" is used once (within its own library)
        // in the "MyValue" def's expression (which doesn't produce an ExpressionRef) and
        // in FunctionCaller via cross-library reference.
        // Instead, compile and search FunctionCaller which contains the ExpressionRef.
        val callerUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!
        // Need to compile dependent library first for the index
        compilationManager.compile(Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionLib.cql")!!)
        val library = compilationManager.compile(callerUri)!!.library!!
        val refUri = callerUri
        val result = AllReferencesVisitor(refUri).visitLibrary(library, "MyValue")
        assertFalse(result.isEmpty(), "Expected at least one ExpressionRef for 'MyValue'")
    }

    @Test
    fun visitExpressionRef_nonMatchingName_returnsEmpty() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!
        compilationManager.compile(Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionLib.cql")!!)
        val library = compilationManager.compile(uri)!!.library!!
        val result = AllReferencesVisitor(uri).visitLibrary(library, "NonExistentSymbol")
        assertTrue(result.isEmpty(), "Expected no references for non-existent symbol")
    }

    @Test
    fun visitFunctionRef_matchingName_returnsLocation() {
        val flUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionLib.cql")!!
        val callerUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!
        compilationManager.compile(flUri)
        val library = compilationManager.compile(callerUri)!!.library!!
        val result = AllReferencesVisitor(callerUri).visitLibrary(library, "Double")
        assertFalse(result.isEmpty(), "Expected at least one FunctionRef for 'Double'")
    }

    @Test
    fun visitValueSetRef_matchingName_returnsLocation() {
        // WithTerminology.cql: define "UseVS": "Beta Blocker Therapy"
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithTerminology.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val result = AllReferencesVisitor(uri).visitLibrary(library, "Beta Blocker Therapy")
        // "Beta Blocker Therapy" is defined as a valueset and referenced once in UseVS.
        // The visitor traverses all nodes and should find the ValueSetRef.
        assertFalse(result.isEmpty(), "Expected at least one ValueSetRef for 'Beta Blocker Therapy'")
    }

    @Test
    fun visitCodeRef_matchingName_returnsLocation() {
        // TerminologyLib.cql: concept "Left Foot Pain": { "Venous foot pain, left" }
        // The concept's code list contains a CodeRef referencing "Venous foot pain, left".
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/TerminologyLib.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val result = AllReferencesVisitor(uri).visitLibrary(library, "Venous foot pain, left")
        assertFalse(result.isEmpty(), "Expected at least one CodeRef for 'Venous foot pain, left'")
    }

    @Test
    fun visitCodeRef_nonMatchingName_returnsEmpty() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/TerminologyLib.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val result = AllReferencesVisitor(uri).visitLibrary(library, "NoSuchCode")
        assertTrue(result.isEmpty(), "Expected no refs for non-existent code name")
    }

    @Test
    fun visitConceptRef_matchingName_returnsLocation() {
        // TerminologyCaller.cql: define "UseConcept": TL."Left Foot Pain"
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/TerminologyCaller.cql")!!
        val termLibUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/TerminologyLib.cql")!!
        compilationManager.compile(termLibUri)
        val library = compilationManager.compile(uri)!!.library!!
        val result = AllReferencesVisitor(uri).visitLibrary(library, "Left Foot Pain")
        // Depending on the compiler, the reference may be ExpressionRef or ConceptRef.
        // Either way, the visitor should find at least one reference.
        assertFalse(result.isEmpty(), "Expected at least one ref for 'Left Foot Pain'")
    }

    @Test
    fun visitCodeSystemRef_matchingName_returnsLocation() {
        // WithTerminology.cql: codesystem "SNOMEDCT", then used in Code '12345' from "SNOMEDCT"
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithTerminology.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val result = AllReferencesVisitor(uri).visitLibrary(library, "SNOMEDCT")
        assertFalse(result.isEmpty(), "Expected at least one CodeSystemRef for 'SNOMEDCT'")
    }

    @Test
    fun visitCodeSystemRef_nonMatchingName_returnsEmpty() {
        val uri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/WithTerminology.cql")!!
        val library = compilationManager.compile(uri)!!.library!!
        val result = AllReferencesVisitor(uri).visitLibrary(library, "NoSuchCS")
        assertTrue(result.isEmpty(), "Expected no refs for non-existent codesystem name")
    }

    @Test
    fun aggregateResult_combinesMultipleLocations() {
        // FunctionLib.cql: define "UseDouble": "Double"(42)
        // The ExpressionRef for "Double" inside UseDouble should be found.
        val flUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionLib.cql")!!
        compilationManager.compile(flUri)
        val library = compilationManager.compile(flUri)!!.library!!
        val result = AllReferencesVisitor(flUri).visitLibrary(library, "Double")
        // "Double" is defined as a function and referenced once within UseDouble.
        assertFalse(result.isEmpty(), "Expected at least one reference to 'Double'")
    }
}
