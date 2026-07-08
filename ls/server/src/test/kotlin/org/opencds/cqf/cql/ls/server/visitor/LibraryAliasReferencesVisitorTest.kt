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

class LibraryAliasReferencesVisitorTest {
    companion object {
        private lateinit var compilationManager: CqlCompilationManager
        private val flUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionLib.cql")!!
        private val callerUri = Uris.parseOrNull("/org/opencds/cqf/cql/ls/server/FunctionCaller.cql")!!

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val cs = TestContentService()
            compilationManager =
                CqlCompilationManager(cs, CompilerOptionsManager(cs), IgContextManager(cs), LibraryResolutionManager(emptyList()))
            compilationManager.compile(flUri)
            compilationManager.compile(callerUri)
        }
    }

    @Test
    fun visitExpressionRef_matchingLibraryName_returnsLocation() {
        // FunctionCaller.cql: FL."MyValue" → ExpressionRef { libraryName="FL", name="MyValue" }
        val library = compilationManager.compile(callerUri)!!.library!!
        val result = LibraryAliasReferencesVisitor(callerUri).visitLibrary(library, "FL")
        assertFalse(result.isEmpty(), "Expected at least one ref with libraryName='FL'")
    }

    @Test
    fun visitExpressionRef_nonMatchingLibraryName_returnsEmpty() {
        val library = compilationManager.compile(callerUri)!!.library!!
        val result = LibraryAliasReferencesVisitor(callerUri).visitLibrary(library, "NotFL")
        assertTrue(result.isEmpty(), "Expected no refs for non-existent alias 'NotFL'")
    }

    @Test
    fun visitFunctionRef_matchingLibraryName_returnsLocation() {
        // FunctionCaller.cql: FL."Double"(3) → FunctionRef { libraryName="FL", name="Double" }
        val library = compilationManager.compile(callerUri)!!.library!!
        val result = LibraryAliasReferencesVisitor(callerUri).visitLibrary(library, "FL")
        assertTrue(
            result.isNotEmpty(),
            "Expected FunctionRef location for FL.\"Double\"",
        )
    }

    @Test
    fun noAliasRefs_inLibraryWithNoIncludes_returnsEmpty() {
        // FunctionLib.cql includes nothing, so no libraryName refs can exist
        val library = compilationManager.compile(flUri)!!.library!!
        val result = LibraryAliasReferencesVisitor(flUri).visitLibrary(library, "FL")
        assertTrue(result.isEmpty(), "Expected no refs in a library that doesn't include anything")
    }
}
