package org.opencds.cqf.cql.ls.server.repository.ig.standard

/**
 * Class that represents the compartment context for a given request within IgRepository only.
 */
class IgStandardRepositoryCompartment {

    val type: String?
    val id: String?

    // Empty context (i.e. no compartment context)
    constructor() {
        this.type = null
        this.id = null
    }

    // Context in the format ResourceType/Id
    constructor(context: String) : this(typeOfContext(context), idOfContext(context))

    // Context in the format type and id
    constructor(type: String, id: String) {
        // Make this lowercase so the path will resolve on Linux (FYI: macOS is case-insensitive)
        this.type = requireNonNullOrEmpty("type", type).lowercase()
        this.id = requireNonNullOrEmpty("id", id)
    }

    fun isEmpty(): Boolean = type == null || id == null

    override fun equals(other: Any?): Boolean {
        if (other !is IgStandardRepositoryCompartment) return false
        return type == other.type && id == other.id
    }

    override fun hashCode(): Int = java.util.Objects.hash(type, id)

    override fun toString(): String =
        "${IgStandardRepositoryCompartment::class.simpleName}[type='$type', id='$id']"

    companion object {
        private fun typeOfContext(context: String): String = context.split("/")[0]
        private fun idOfContext(context: String): String = context.split("/")[1]

        private fun requireNonNullOrEmpty(name: String, value: String?): String {
            if (value.isNullOrEmpty()) {
                throw IllegalArgumentException("$name cannot be null or empty")
            }
            return value
        }
    }
}
