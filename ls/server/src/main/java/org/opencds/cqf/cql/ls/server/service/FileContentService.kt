package org.opencds.cqf.cql.ls.server.service

import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.IOFileFilter
import org.apache.commons.io.filefilter.TrueFileFilter
import org.cqframework.cql.cql2elm.model.Version
import org.eclipse.lsp4j.WorkspaceFolder
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Paths

// NOTE: This implementation is naive and assumes library file names will always take the form:
// <filename>[-<version>].cql
class FileContentService(protected val workspaceFolders: List<WorkspaceFolder>) : ContentService {

    companion object {
        private val log = LoggerFactory.getLogger(FileContentService::class.java)

        @JvmStatic
        fun searchFolder(directory: URI, libraryIdentifier: VersionedIdentifier): File? {
            val path = try {
                Paths.get(directory)
            } catch (e: Exception) {
                log.warn("error searching directory $directory. Skipping.", e)
                return null
            }

            val libraryName = libraryIdentifier.id ?: return null
            val libraryPath = path.resolve(
                "$libraryName${if (libraryIdentifier.version != null) "-${libraryIdentifier.version}" else ""}.cql"
            )
            val libraryFile = libraryPath.toFile()
            return if (libraryFile.exists()) libraryFile
            else nearestMatch(path.toFile(), libraryName, libraryIdentifier.version)
        }

        private fun ioFilter(name: String): IOFileFilter = object : IOFileFilter {
            override fun accept(dir: File, filename: String) =
                filename.startsWith(name) && filename.endsWith(".cql")

            override fun accept(file: File) =
                if (file.isFile) accept(file, file.name) else false
        }

        private fun nearestMatch(directory: File, name: String, version: String?): File? {
            val files = FileUtils.listFiles(directory, ioFilter(name), TrueFileFilter.INSTANCE)
            if (files == null || files.isEmpty()) return null

            var mostRecentFile: File? = null
            var mostRecent: Version? = null
            val requestedVersion = if (version != null) try { Version(version) } catch (e: Exception) { null } else null

            for (file in files) {
                val fileName = file.name
                val (parsedName, v) = getNameAndVersion(fileName)

                if (!parsedName.equals(name, ignoreCase = true)) continue

                when {
                    v != null && requestedVersion != null && v.compareTo(requestedVersion) == 0 -> return file
                    v == null -> return file
                    (requestedVersion == null || v.compatibleWith(requestedVersion)) &&
                        (mostRecent == null || v.compareTo(mostRecent) > 0) -> {
                        mostRecent = v
                        mostRecentFile = file
                    }
                }
            }
            return mostRecentFile
        }

        private fun getVersion(version: String): Version? {
            return try { Version(version) } catch (e: Exception) { null }
        }

        private fun getNameAndVersion(fileName: String): Pair<String, Version?> {
            var name = fileName
            val indexOfExtension = name.lastIndexOf(".")
            if (indexOfExtension >= 0) name = name.substring(0, indexOfExtension)

            val indexOfVersionSeparator = name.lastIndexOf("-")
            var version: Version? = null
            if (indexOfVersionSeparator >= 0) {
                version = getVersion(name.substring(indexOfVersionSeparator + 1))
                if (version != null) name = name.substring(0, indexOfVersionSeparator)
            }
            return Pair(name, version)
        }
    }

    override fun locate(root: URI, identifier: VersionedIdentifier): Set<URI> {
        requireNotNull(root)
        requireNotNull(identifier)

        val uris = mutableSetOf<URI>()
        for (w in workspaceFolders) {
            val folderUri = Uris.parseOrNull(w.uri) ?: continue
            if (folderUri.relativize(root) == root) continue

            val file = searchFolder(root, identifier)
            if (file != null && file.exists()) {
                uris.add(file.toURI())
            }
        }
        return uris
    }

    override fun read(uri: URI): InputStream? {
        return try {
            BufferedInputStream(FileInputStream(File(uri)))
        } catch (e: Exception) {
            null
        }
    }
}
