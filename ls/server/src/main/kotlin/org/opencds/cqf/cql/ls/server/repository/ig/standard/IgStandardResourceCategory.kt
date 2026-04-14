package org.opencds.cqf.cql.ls.server.repository.ig.standard

enum class IgStandardResourceCategory {
    DATA,
    TERMINOLOGY,
    CONTENT,
    ;

    companion object {
        private val TERMINOLOGY_RESOURCES = setOf("ValueSet", "CodeSystem")
        private val CONTENT_RESOURCES =
            setOf(
                "Library",
                "Questionnaire",
                "Measure",
                "PlanDefinition",
                "StructureDefinition",
                "ActivityDefinition",
            )

        fun forType(resourceType: String): IgStandardResourceCategory {
            return when {
                TERMINOLOGY_RESOURCES.contains(resourceType) -> TERMINOLOGY
                CONTENT_RESOURCES.contains(resourceType) -> CONTENT
                else -> DATA
            }
        }
    }
}
