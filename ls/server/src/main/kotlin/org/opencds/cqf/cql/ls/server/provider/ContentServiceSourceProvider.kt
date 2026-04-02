package org.opencds.cqf.cql.ls.server.provider

import kotlinx.io.Source
import org.cqframework.cql.cql2elm.LibrarySourceProvider
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Converters
import java.net.URI

class ContentServiceSourceProvider(
    private val root: URI,
    private val contentService: ContentService
) : LibrarySourceProvider {

    override fun getLibrarySource(libraryIdentifier: VersionedIdentifier): Source? {
        val inputStream = contentService.read(root, libraryIdentifier) ?: return null
        return Converters.inputStreamToSource(inputStream)
    }
}
