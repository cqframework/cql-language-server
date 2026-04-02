package org.opencds.cqf.cql.ls.server.service

import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.ls.core.ContentService
import java.io.InputStream
import java.net.URI

class FederatedContentService(
    private val activeContentService: ActiveContentService,
    private val fileContentService: ContentService
) : ContentService {

    override fun locate(root: URI, identifier: VersionedIdentifier): Set<URI> {
        requireNotNull(root)
        requireNotNull(identifier)
        val locations = activeContentService.locate(root, identifier).toMutableSet()
        locations.addAll(fileContentService.locate(root, identifier))
        return locations
    }

    override fun read(uri: URI): InputStream? {
        requireNotNull(uri)
        return if (activeContentService.activeUris().contains(uri)) {
            activeContentService.read(uri)
        } else {
            fileContentService.read(uri)
        }
    }
}
