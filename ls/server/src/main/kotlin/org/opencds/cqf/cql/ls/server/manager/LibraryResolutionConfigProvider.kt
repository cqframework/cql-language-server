package org.opencds.cqf.cql.ls.server.manager

import java.net.URI

interface LibraryResolutionConfigProvider {
    fun getConfig(root: URI): LibraryResolutionConfig
}
