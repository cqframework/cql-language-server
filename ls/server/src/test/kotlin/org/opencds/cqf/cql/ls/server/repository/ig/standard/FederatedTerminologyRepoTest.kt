package org.opencds.cqf.cql.ls.server.repository.ig.standard

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException
import ca.uhn.fhir.util.BundleUtil
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.ValueSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.fhir.utility.Ids
import org.opencds.cqf.fhir.utility.search.Searches
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for [FederatedTerminologyRepo].
 *
 * Each test creates minimal `vocabulary/valueset/` directory trees under @TempDir
 * so tests run entirely on the local filesystem without needing classpath fixtures.
 */
class FederatedTerminologyRepoTest {

    private val fhirContext: FhirContext = FhirContext.forR4Cached()

    @TempDir
    lateinit var tempDir: Path

    private lateinit var projectA: Path
    private lateinit var projectB: Path

    @BeforeEach
    fun setup() {
        projectA = tempDir.resolve("projectA").also { Files.createDirectories(it) }
        projectB = tempDir.resolve("projectB").also { Files.createDirectories(it) }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Creates `{projectRoot}/vocabulary/valueset/{id}.json` with a minimal ValueSet. */
    private fun writeValueSet(projectRoot: Path, id: String, url: String = "http://example.org/$id") {
        val vsDir = projectRoot.resolve("vocabulary/valueset")
        Files.createDirectories(vsDir)
        vsDir.resolve("$id.json").toFile().writeText(
            """{"resourceType":"ValueSet","id":"$id","url":"$url"}""",
        )
    }

    private fun searchById(repo: FederatedTerminologyRepo, id: String): List<ValueSet> {
        val result = repo.search(Bundle::class.java, ValueSet::class.java, Searches.byId(id), null)
        @Suppress("UNCHECKED_CAST")
        return BundleUtil.toListOfResources(fhirContext, result) as List<ValueSet>
    }

    // -----------------------------------------------------------------------
    // search: first project has the ValueSet
    // -----------------------------------------------------------------------

    @Test
    fun search_primaryProjectHasValueSet_returnsIt() {
        writeValueSet(projectA, "VS1")

        val repo = FederatedTerminologyRepo(fhirContext, listOf(projectA, projectB))
        val results = searchById(repo, "VS1")

        assertEquals(1, results.size)
        assertEquals("VS1", results.first().idElement.idPart)
    }

    // -----------------------------------------------------------------------
    // search: only second project has the ValueSet (federation falls through)
    // -----------------------------------------------------------------------

    @Test
    fun search_onlySecondProjectHasValueSet_returnsIt() {
        writeValueSet(projectB, "VS2")

        val repo = FederatedTerminologyRepo(fhirContext, listOf(projectA, projectB))
        val results = searchById(repo, "VS2")

        assertEquals(1, results.size)
        assertEquals("VS2", results.first().idElement.idPart)
    }

    // -----------------------------------------------------------------------
    // search: both projects have a ValueSet with the same id (first wins)
    // -----------------------------------------------------------------------

    @Test
    fun search_bothProjectsHaveSameId_firstWins() {
        writeValueSet(projectA, "VSShared", url = "http://example.org/A")
        writeValueSet(projectB, "VSShared", url = "http://example.org/B")

        val repo = FederatedTerminologyRepo(fhirContext, listOf(projectA, projectB))
        val results = searchById(repo, "VSShared")

        assertEquals(1, results.size)
        assertEquals("http://example.org/A", results.first().url)
    }

    // -----------------------------------------------------------------------
    // search: no project has the ValueSet → empty bundle, no exception
    // -----------------------------------------------------------------------

    @Test
    fun search_noProjectHasValueSet_returnsEmptyBundle() {
        val repo = FederatedTerminologyRepo(fhirContext, listOf(projectA, projectB))
        val results = searchById(repo, "DoesNotExist")

        assertTrue(results.isEmpty())
    }

    // -----------------------------------------------------------------------
    // search: empty delegate list → empty bundle via fallback constructor
    // -----------------------------------------------------------------------

    @Test
    fun search_emptyDelegateList_returnsEmptyBundle() {
        val repo = FederatedTerminologyRepo(fhirContext, emptyList())
        val result = repo.search(Bundle::class.java, ValueSet::class.java, Searches.byId("Any"), null)

        assertNotNull(result)
        assertTrue(BundleUtil.toListOfResources(fhirContext, result).isEmpty())
    }

    // -----------------------------------------------------------------------
    // read: second project has the resource
    // -----------------------------------------------------------------------

    @Test
    fun read_secondProjectHasValueSet_returnsIt() {
        writeValueSet(projectB, "VS-READ")

        val repo = FederatedTerminologyRepo(fhirContext, listOf(projectA, projectB))
        val vs = repo.read(ValueSet::class.java, Ids.newId(ValueSet::class.java, "VS-READ"), null)

        assertNotNull(vs)
        assertEquals("VS-READ", vs.idElement.idPart)
    }

    // -----------------------------------------------------------------------
    // read: resource not in any project → ResourceNotFoundException
    // -----------------------------------------------------------------------

    @Test
    fun read_resourceNotInAnyProject_throwsResourceNotFoundException() {
        val repo = FederatedTerminologyRepo(fhirContext, listOf(projectA, projectB))

        assertThrows(ResourceNotFoundException::class.java) {
            repo.read(ValueSet::class.java, Ids.newId(ValueSet::class.java, "DoesNotExist"), null)
        }
    }

    // -----------------------------------------------------------------------
    // delegates list matches inputPaths (construction sanity check)
    // -----------------------------------------------------------------------

    @Test
    fun constructor_createsDelegateForEachValidPath() {
        writeValueSet(projectA, "dummy")  // ensure vocabulary dir exists for convention detection
        writeValueSet(projectB, "dummy")

        val repo = FederatedTerminologyRepo(fhirContext, listOf(projectA, projectB))
        assertEquals(2, repo.delegates.size)
    }
}
