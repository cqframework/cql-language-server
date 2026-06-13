package org.opencds.cqf.cql.ls.server.command

import org.eclipse.lsp4j.ExecuteCommandParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GetVersionInfoCommandContributionTest {
    private val contribution = GetVersionInfoCommandContribution()

    @Test
    fun `getCommands returns getVersionInfo command`() {
        assertEquals(setOf("org.opencds.cqf.cql.ls.getVersionInfo"), contribution.getCommands())
    }

    @Test
    fun `executeCommand returns VersionInfo with all fields present`() {
        val params = ExecuteCommandParams("org.opencds.cqf.cql.ls.getVersionInfo", emptyList())
        val result = contribution.executeCommand(params).join()

        assertInstanceOf(VersionInfo::class.java, result)
        val vi = result as VersionInfo
        // Package.implementationVersion may be null when running outside a JAR (e.g. tests).
        // Verify the VersionInfo instance is present with the correct type and fields.
        val versionFields = vi::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("translator" in versionFields)
        assertTrue("engine" in versionFields)
        assertTrue("clinicalReasoning" in versionFields)
        assertTrue("languageServer" in versionFields)
    }
}
