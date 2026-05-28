package org.opencds.cqf.cql.ls.server.command

import com.google.gson.Gson
import org.eclipse.lsp4j.ExecuteCommandParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager
import org.opencds.cqf.cql.ls.server.service.TestContentService

class ExecuteCqlCommandContributionTest {
    companion object {
        private lateinit var contribution: ExecuteCqlCommandContribution

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val cs = TestContentService()
            contribution = ExecuteCqlCommandContribution(IgContextManager(cs), cs, LibraryResolutionManager(emptyList()))
        }
    }

    @Test
    fun `getCommands returns executeCql command`() {
        assertEquals(setOf("org.opencds.cqf.cql.ls.executeCql"), contribution.getCommands())
    }

    @Test
    fun `executeCommand returns structured response for One library`() {
        // TestContentService resolves "One" from classpath regardless of the libraryUri root
        val request =
            ExecuteCqlRequest(
                fhirVersion = "R4",
                rootDir = null,
                optionsPath = null,
                libraries =
                    listOf(
                        LibraryRequest(
                            libraryName = "One",
                            libraryUri = "file:///any/path",
                            libraryVersion = null,
                            terminologyUri = null,
                            model = null,
                            context = null,
                            parameters = emptyList(),
                        ),
                    ),
            )
        val element = Gson().toJsonTree(request)
        val params = ExecuteCommandParams("org.opencds.cqf.cql.ls.executeCql", listOf(element))
        val response = contribution.executeCommand(params).join() as ExecuteCqlResponse

        assertEquals(1, response.results.size)
        assertEquals("One", response.results[0].libraryName)
        assertTrue(
            response.results[0].expressions.any { it.name == "One" },
            "Expected expression 'One' in results",
        )
        assertTrue(response.logs.isEmpty())
    }
}
