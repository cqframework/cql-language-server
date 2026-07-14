package org.opencds.cqf.cql.ls.server.command.fhirop

import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.manager.LibraryResolutionManager

/**
 * Collaborators available to every [FhirOperationHandler] — the same three
 * [org.opencds.cqf.cql.ls.server.command.ExecuteCqlCommandContribution] receives, so handlers
 * build their [org.opencds.cqf.fhir.cql.Engines]/repository the same way [org.opencds.cqf.cql.ls.server.command.CqlEvaluator] does.
 */
data class FhirOperationContext(
    val igContextManager: IgContextManager,
    val contentService: ContentService,
    val libraryResolutionManager: LibraryResolutionManager,
)
