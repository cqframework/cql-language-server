package org.opencds.cqf.cql.ls.server.validation.profile

import org.hl7.fhir.utilities.validation.ValidationMessage
import org.opencds.cqf.cql.ls.server.validation.testcase.IssueSource
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidationIssue
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidationSeverity
import java.nio.file.Path

/** Maps a core validator message onto the extension-facing [ValidationIssue] model. */
fun ValidationMessage.toCoreIssue(file: Path): ValidationIssue =
    ValidationIssue(
        severity =
            when (level) {
                ValidationMessage.IssueSeverity.FATAL, ValidationMessage.IssueSeverity.ERROR -> ValidationSeverity.ERROR
                ValidationMessage.IssueSeverity.WARNING -> ValidationSeverity.WARNING
                else -> ValidationSeverity.INFORMATION
            },
        code = messageId?.takeIf { it.isNotBlank() } ?: ProfileIssuePolicy.NO_MESSAGE_ID,
        message = message ?: "",
        filePath = file.toString(),
        jsonPath = location,
        line = line.takeIf { it > 0 },
        source = IssueSource.CORE,
    )

/**
 * The noise policy for core engine issues: drops what authors can't act on and downgrades rules
 * MADiE-generated content routinely breaks.
 *
 * An entry matches an issue when it equals the issue code, matches its `#invariant` suffix
 * (`us-core-17` matches `...us-core-practitioner#us-core-17`), or, for entries containing a space,
 * appears in the message text (core reports some messages without an id).
 */
class ProfileIssuePolicy(
    private val mode: EngineMode,
    suppressMessageIds: List<String>? = null,
    downgradeMessageIds: List<String>? = null,
) {
    companion object {
        const val NO_MESSAGE_ID = "CORE_NO_MESSAGE_ID"

        /** Terminology noise when running without a terminology server; QUICK only. */
        val QUICK_SUPPRESS =
            listOf(
                "Unable to validate code without using server",
                "Error_validating_code_running_without_terminology_services",
                "Terminology_PassThrough_TX_Message",
            )

        /** NPI check digit/length (MADiE writes fake NPIs) and repeated MeasureReport population ids. */
        val DEFAULT_DOWNGRADE = listOf("us-core-16", "us-core-17", "DUPLICATE_ID")

        private val UNKNOWN_PROFILE_IDS = listOf("Validation_VAL_Profile_Unknown", "VALIDATION_VAL_PROFILE_UNKNOWN_NOT_POLICY")
        private val URL = Regex("https?://[^\\s'\"|,)]+")

        fun matches(
            entry: String,
            issue: ValidationIssue,
        ): Boolean =
            entry.isNotBlank() &&
                (
                    issue.code == entry ||
                        issue.code.endsWith("#$entry") ||
                        (entry.contains(' ') && issue.message.contains(entry))
                )

        /** The profile URL of a core "profile not found" issue, or null if [issue] isn't one. */
        fun unknownProfileUrl(issue: ValidationIssue): String? {
            if (issue.source != IssueSource.CORE || UNKNOWN_PROFILE_IDS.none { it.equals(issue.code, ignoreCase = true) }) return null
            return URL.find(issue.message)?.value?.trimEnd('.') ?: "(unknown)"
        }

        /** The one-time hint for [issues] that report profiles the engine hasn't loaded. */
        fun unknownProfileHint(issues: List<ValidationIssue>): String? {
            val urls = issues.mapNotNull { unknownProfileUrl(it) }.distinct()
            if (urls.isEmpty()) return null
            val more = if (urls.size > 1) " (and ${urls.size - 1} more)" else ""
            return "Profile ${urls.first()}$more isn't loaded; add its package to validation.packages"
        }
    }

    private val suppress = (if (mode == EngineMode.QUICK) QUICK_SUPPRESS else emptyList()) + suppressMessageIds.orEmpty()
    private val downgrade = DEFAULT_DOWNGRADE + downgradeMessageIds.orEmpty()

    data class Result(val issues: List<ValidationIssue>, val suppressedCount: Int)

    /**
     * Applies the policy. QUICK also drops informational messages; FULL keeps them. Dropped issues
     * are counted in [Result.suppressedCount].
     */
    fun apply(issues: List<ValidationIssue>): Result {
        var suppressed = 0
        val kept = mutableListOf<ValidationIssue>()
        for (issue in issues) {
            if ((mode == EngineMode.QUICK && issue.severity == ValidationSeverity.INFORMATION) || suppress.any { matches(it, issue) }) {
                suppressed++
                continue
            }
            kept.add(
                if (issue.severity == ValidationSeverity.ERROR && downgrade.any { matches(it, issue) }) {
                    issue.copy(severity = ValidationSeverity.WARNING)
                } else {
                    issue
                },
            )
        }
        return Result(kept, suppressed)
    }
}
