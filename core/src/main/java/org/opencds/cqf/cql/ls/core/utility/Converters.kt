package org.opencds.cqf.cql.ls.core.utility

import kotlinx.io.Buffer
import kotlinx.io.Source
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object Converters {
    @JvmStatic
    @Throws(IOException::class)
    fun inputStreamToString(inputStream: InputStream): String {
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(line)
            }
        }
        return sb.toString()
    }

    @JvmStatic
    fun stringToSource(text: String): Source {
        val buffer = Buffer()
        val bytes = text.toByteArray()
        buffer.write(bytes, 0, bytes.size)
        return buffer
    }

    @JvmStatic
    @Throws(IOException::class)
    fun inputStreamToSource(inputStream: InputStream): Source {
        return stringToSource(inputStreamToString(inputStream))
    }
}
