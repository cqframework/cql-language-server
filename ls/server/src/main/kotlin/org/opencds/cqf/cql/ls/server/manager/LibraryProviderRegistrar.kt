package org.opencds.cqf.cql.ls.server.manager

import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.quick.FhirLibrarySourceProvider

/**
 * Registers the two library-source-provider steps that are genuinely identical between the
 * design-time compiler ([CqlCompilationManager.createLibraryManager]) and the debug/execute
 * engine ([org.opencds.cqf.cql.ls.server.command.CqlEvaluator.evaluateBatch]), so the two paths
 * can't drift apart the way they previously did (each hand-copied this sequence, kept in sync
 * only by code comments referencing the other file).
 *
 * The earlier providers in the chain (workspace-file [org.opencds.cqf.cql.ls.server.provider.FederatedLibrarySourceProvider]
 * and npm setup via [IgContextManager.setupLibraryManager]) are deliberately NOT included here:
 * they are not actually identical between the two call sites. `CqlEvaluator` builds its
 * `LibraryManager` via `Engines.forRepository`, which may have already registered npm support
 * itself (via `evaluationSettings.npmProcessor`) before this point, so it only falls back to
 * [IgContextManager.setupLibraryManager] when that didn't happen. `CqlCompilationManager` has no
 * such engine-internal registration and always calls [IgContextManager.setupLibraryManager]
 * unconditionally. Force-unifying that conditional would risk changing real behavior for a
 * "consolidation" that's supposed to be behavior-preserving — only the tail, unconditional steps
 * below are safe to share as-is.
 *
 * Must run AFTER the workspace/npm providers are registered — workspace namespaces should not
 * override npm-registered ones (a namespace already known to npm should win), and the bundled
 * FHIRHelpers provider must be registered last so it's the lowest-priority fallback.
 */
fun registerCommonLibraryProviders(
    libraryManager: LibraryManager,
    libraryResolutionManager: LibraryResolutionManager,
) {
    libraryResolutionManager.registerWorkspaceNamespaces(libraryManager)
    libraryManager.librarySourceLoader.registerProvider(FhirLibrarySourceProvider())
}
