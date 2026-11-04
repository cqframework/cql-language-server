package org.opencds.cqf.cql.ls.server.validation.profile

import org.hl7.fhir.r5.utils.validation.constants.ReferenceValidationPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReferencePolicyAdvisorTest {
    private fun policy(
        advisor: ReferencePolicyAdvisor,
        url: String?,
    ) = advisor.policyForReference(null, null, "Encounter.subject", url, null)

    @Test
    fun `local references follow the mode, absolute urls are ignored`() {
        val advisor = ReferencePolicyAdvisor()
        for (url in listOf("Patient/p1", "urn:uuid:abc", "#contained")) {
            advisor.mode = EngineMode.QUICK
            assertEquals(ReferenceValidationPolicy.CHECK_EXISTS_AND_TYPE, policy(advisor, url), url)
            advisor.mode = EngineMode.FULL
            assertEquals(ReferenceValidationPolicy.CHECK_VALID, policy(advisor, url), url)
        }
        for (url in listOf("http://example.org/Patient/1", "https://example.org/x", "urn:oid:1.2.3", null)) {
            assertEquals(ReferenceValidationPolicy.IGNORE, policy(advisor, url), url)
        }
    }

    @Test
    fun `checkReferences false ignores every reference in both modes`() {
        val advisor = ReferencePolicyAdvisor().apply { checkReferences = false }
        for (mode in EngineMode.entries) {
            advisor.mode = mode
            assertEquals(ReferenceValidationPolicy.IGNORE, policy(advisor, "Patient/p1"), "$mode")
            assertEquals(ReferenceValidationPolicy.IGNORE, policy(advisor, "urn:uuid:abc"), "$mode")
            assertEquals(ReferenceValidationPolicy.IGNORE, advisor.referencePolicy, "$mode")
        }
    }
}
