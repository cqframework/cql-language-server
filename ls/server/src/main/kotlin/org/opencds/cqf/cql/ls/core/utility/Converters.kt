package org.opencds.cqf.cql.ls.core.utility

import kotlinx.io.Buffer
import kotlinx.io.Source
import java.io.IOException
import java.io.InputStream

object Converters {
    @Throws(IOException::class)
    fun inputStreamToString(inputStream: InputStream): String = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun stringToSource(text: String): Source {
        val buffer = Buffer()
        buffer.write(text.toByteArray())
        return buffer
    }

    @Throws(IOException::class)
    fun inputStreamToSource(inputStream: InputStream): Source = stringToSource(inputStreamToString(inputStream))
}
