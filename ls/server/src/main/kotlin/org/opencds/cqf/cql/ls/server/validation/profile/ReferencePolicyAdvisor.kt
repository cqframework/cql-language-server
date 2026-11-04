package org.opencds.cqf.cql.ls.server.validation.profile

import org.hl7.fhir.r5.utils.validation.IResourceValidator
import org.hl7.fhir.r5.utils.validation.IValidationPolicyAdvisor
import org.hl7.fhir.r5.utils.validation.constants.ReferenceValidationPolicy
import org.hl7.fhir.validation.instance.advisor.BasePolicyAdvisorForFullValidation

/**
 * Checks local references (relative, `urn:uuid`, contained) and ignores absolute URLs. QUICK checks
 * existence and target type; FULL also validates the target against the element's targetProfile.
 * With [checkReferences] false every reference is IGNOREd. Not thread-safe: [mode] and
 * [checkReferences] are per call. Message suppression is deliberately not done here but in
 * [ProfileIssuePolicy], after mapping, so suppressed messages can be counted.
 */
class ReferencePolicyAdvisor(
    var mode: EngineMode = EngineMode.QUICK,
) : BasePolicyAdvisorForFullValidation(ReferenceValidationPolicy.IGNORE, emptySet()) {
    var checkReferences: Boolean = true

    val localPolicy: ReferenceValidationPolicy
        get() =
            when {
                !checkReferences -> ReferenceValidationPolicy.IGNORE
                mode == EngineMode.FULL -> ReferenceValidationPolicy.CHECK_VALID
                else -> ReferenceValidationPolicy.CHECK_EXISTS_AND_TYPE
            }

    override fun policyForReference(
        validator: IResourceValidator?,
        appContext: Any?,
        path: String?,
        url: String?,
        destinationType: IValidationPolicyAdvisor.ReferenceDestinationType?,
    ): ReferenceValidationPolicy = if (url == null || isAbsoluteUrl(url)) ReferenceValidationPolicy.IGNORE else localPolicy

    override fun getReferencePolicy(): ReferenceValidationPolicy = localPolicy
}
