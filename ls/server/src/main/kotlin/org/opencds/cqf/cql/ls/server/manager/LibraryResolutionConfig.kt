package org.opencds.cqf.cql.ls.server.manager

enum class LibraryResolutionMode { STRICT, PATCH_FLEXIBLE }

data class LibraryResolutionConfig(
    val mode: LibraryResolutionMode = LibraryResolutionMode.PATCH_FLEXIBLE,
    val unqualifiedCrossProjectSearch: Boolean = false,
    val projectSearchOrder: List<String> = emptyList(),
    val projectSearchExclude: Set<String> = emptySet(),
)
