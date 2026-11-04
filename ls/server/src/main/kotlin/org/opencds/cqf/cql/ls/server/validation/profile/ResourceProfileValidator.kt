package org.opencds.cqf.cql.ls.server.validation.profile

import org.opencds.cqf.cql.ls.server.validation.testcase.ValidationIssue
import java.nio.file.Path

/**
 * Validates test case resources against their FHIR profiles. Kept behind an interface so the
 * JVM-only HL7 core engine can be swapped (or faked in tests).
 */
interface ResourceProfileValidator {
    /**
     * Validates each JSON file in [files] (a resource or a bundle). [index] resolves `Type/id`,
     * `urn:uuid` and fullUrl references locally. With [checkReferences] false, local references
     * are not checked at all. Returned issues carry `source = CORE`.
     */
    fun validate(
        files: List<Path>,
        index: CaseResourceIndex,
        mode: EngineMode,
        checkReferences: Boolean = true,
    ): List<ValidationIssue>
}

/** The two engine-backed profile modes (the wire-level `OFF` never reaches a validator). */
enum class EngineMode {
    QUICK,
    FULL,
}
