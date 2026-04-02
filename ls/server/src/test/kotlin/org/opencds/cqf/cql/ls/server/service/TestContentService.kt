package org.opencds.cqf.cql.ls.server.service

import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.ls.core.ContentService
import java.io.InputStream
import java.net.URI

class TestContentService : ContentService {

    override fun locate(root: URI, libraryIdentifier: VersionedIdentifier): Set<URI> =
        setOf(URI("/org/opencds/cqf/cql/ls/server/${libraryIdentifier.id}.cql"))

    override fun read(uri: URI): InputStream? =
        TestContentService::class.java.getResourceAsStream(uri.toString())
}
