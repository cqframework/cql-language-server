package org.opencds.cqf.cql.ls.core.utility

import org.apache.commons.lang3.SystemUtils
import java.io.File
import java.net.URI

object Uris {
    private const val FILE_UNC_PREFIX = "file:////"
    private const val FILE_SCHEME = "file"

    @JvmStatic
    fun getHead(uri: URI): URI {
        val path = uri.rawPath ?: return uri
        val index = path.lastIndexOf("/")
        return if (index > -1) withPath(uri, path.substring(0, index)) ?: uri else uri
    }

    @JvmStatic
    fun withPath(
        uri: URI,
        path: String,
    ): URI? {
        return try {
            URI.create(
                (if (uri.scheme != null) "${uri.scheme}:" else "") +
                    "//" + createAuthority(uri.rawAuthority) + createPath(path) +
                    createQuery(uri.rawQuery) + createFragment(uri.rawFragment),
            )
        } catch (e: Exception) {
            null
        }
    }

    @JvmStatic
    fun addPath(
        uri: URI,
        path: String,
    ): URI? {
        return withPath(uri, stripTrailingSlash(uri.rawPath) + createPath(path))
    }

    @JvmStatic
    fun parseOrNull(uriString: String): URI? {
        return try {
            var uri = URI(uriString)
            if (SystemUtils.IS_OS_WINDOWS && FILE_SCHEME == uri.scheme) {
                uri = File(uri.schemeSpecificPart).toURI()
            }
            uri
        } catch (e: Exception) {
            null
        }
    }

    @JvmStatic
    fun toClientUri(uri: URI?): String? {
        if (uri == null) return null
        var uriString = uri.toString()
        if (SystemUtils.IS_OS_WINDOWS && uriString.startsWith(FILE_UNC_PREFIX)) {
            uriString = uriString.replace(FILE_UNC_PREFIX, "file://")
        }
        return uriString
    }

    private fun createAuthority(rawAuthority: String?) = rawAuthority ?: ""

    private fun stripTrailingSlash(path: String?): String {
        if (path.isNullOrEmpty()) return ""
        return if (path.endsWith("/")) path.dropLast(1) else path
    }

    private fun createPath(pathValue: String?) = ensurePrefix("/", pathValue)

    private fun createQuery(queryValue: String?) = ensurePrefix("?", queryValue)

    private fun createFragment(fragmentValue: String?) = ensurePrefix("#", fragmentValue)

    private fun ensurePrefix(
        prefix: String,
        value: String?,
    ): String {
        if (value.isNullOrEmpty()) return ""
        return if (value.startsWith(prefix)) value else prefix + value
    }
}
