package org.opencds.cqf.cql.ls.core

import org.apache.commons.lang3.NotImplementedException
import org.hl7.elm.r1.VersionedIdentifier
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.net.URI

interface ContentService {
    fun locate(
        root: URI,
        identifier: VersionedIdentifier,
    ): Set<URI> {
        throw NotImplementedException()
    }

    fun read(
        root: URI,
        identifier: VersionedIdentifier,
    ): InputStream? {
        val locations = locate(root, identifier)
        if (locations.isEmpty()) return null

        if (locations.size > 1) {
            val allLocations = locations.joinToString("%n") { it.toString() }
            throw IllegalStateException(
                "more than one location was found for library: ${identifier.id} " +
                    "version: ${identifier.version} in the current workspace:%n$allLocations",
            )
        }
        return read(locations.first())
    }

    fun read(uri: URI): InputStream? {
        return try {
            uri.toURL().openStream()
        } catch (e: Exception) {
            log.warn("error opening stream for: $uri", e)
            null
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ContentService::class.java)
    }
}
