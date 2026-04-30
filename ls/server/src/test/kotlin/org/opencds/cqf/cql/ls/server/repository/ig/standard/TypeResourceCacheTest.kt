package org.opencds.cqf.cql.ls.server.repository.ig.standard

import ca.uhn.fhir.context.FhirContext
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Library
import org.hl7.fhir.r4.model.ValueSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.fhir.test.Resources
import org.opencds.cqf.fhir.utility.search.Searches
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the directory-level [IgStandardRepository.typeResourceCache].
 *
 * The cache prevents repeated [Files.walk] scans when [IgStandardRepository.search]
 * is called multiple times for the same resource type (e.g., ValueSet lookups during
 * terminology expansion across many patients).
 */
class TypeResourceCacheTest {
    private val fhirContext = FhirContext.forR4Cached()

    @TempDir
    lateinit var tempDir: Path

    private fun createRepository(): IgStandardRepository {
        Resources.copyFromJar("/sampleIgs/ig/standard/directoryPerType/standard", tempDir)
        return IgStandardRepository(fhirContext, tempDir)
    }

    @Test
    fun `repeated search returns same results from cache`() {
        val repo = createRepository()

        val first = repo.search(Bundle::class.java, Library::class.java, Searches.ALL)
        val second = repo.search(Bundle::class.java, Library::class.java, Searches.ALL)

        assertEquals(first.entry.size, second.entry.size)
    }

    @Test
    fun `new resource on disk is not visible until clearCache is called`() {
        val repo = createRepository()

        // Warm the cache for Library.
        val beforeCount = repo.search(Bundle::class.java, Library::class.java, Searches.ALL).entry.size

        // Write a new Library file directly to disk, bypassing the repo API so resourceCache
        // is NOT updated — only the filesystem changes.
        val libDir = tempDir.resolve("resources/library")
        val newFile = libDir.resolve("Library-extra.json")
        newFile.toFile().writeText("""{"resourceType":"Library","id":"extra","type":{"coding":[{"code":"logic-library"}]},"content":[]}""")

        // Cache is still warm — new file is invisible to search().
        val cachedCount = repo.search(Bundle::class.java, Library::class.java, Searches.ALL).entry.size
        assertEquals(beforeCount, cachedCount, "typeResourceCache should hide the new file")

        // After clearCache(), the directory is re-scanned and the new file is visible.
        repo.clearCache()
        val afterCount = repo.search(Bundle::class.java, Library::class.java, Searches.ALL).entry.size
        assertEquals(beforeCount + 1, afterCount, "clearCache() should expose the newly written file")
    }

    @Test
    fun `cache is independent per resource type`() {
        val repo = createRepository()

        val libraries = repo.search(Bundle::class.java, Library::class.java, Searches.ALL)
        val valueSets = repo.search(Bundle::class.java, ValueSet::class.java, Searches.ALL)

        // Both types cached independently — counts are not mixed.
        assertEquals(
            repo.search(Bundle::class.java, Library::class.java, Searches.ALL).entry.size,
            libraries.entry.size,
        )
        assertEquals(
            repo.search(Bundle::class.java, ValueSet::class.java, Searches.ALL).entry.size,
            valueSets.entry.size,
        )
    }
}
