package org.opencds.cqf.cql.ls.core.utility

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

class ConvertersTest {
    @Test
    fun should_returnInstance_when_creatingConverter() {
        // Converters is a Kotlin object (singleton); verify it is accessible
        assertNotNull(Converters)
    }

    @Test
    fun should_returnString_when_inputStreamExists() {
        val expected = "The quick brown fox jumps over the lazy dog"
        val actual = Converters.inputStreamToString(ByteArrayInputStream(expected.toByteArray(StandardCharsets.UTF_8)))
        assertEquals(expected, actual)
    }

    @Test
    fun should_returnStringWithLineBreaks_when_inputStreamHasLineBreaksExists() {
        val expected = "the first day in spring –\na wind from the ocean\nbut no ocean in sight"
        val actual = Converters.inputStreamToString(ByteArrayInputStream(expected.toByteArray(StandardCharsets.UTF_8)))
        assertEquals(expected, actual)
    }

    @Test
    fun should_throwIOException_when_inputStreamToStringHasAnIOError() {
        val inputStream =
            object : InputStream() {
                override fun read(): Int = throw IOException("Simulated failure")
            }
        assertThrows(IOException::class.java) { Converters.inputStreamToString(inputStream) }
    }

    @Test
    fun should_returnSource_when_stringExists() {
        val expected = Converters.stringToSource("The quick brown fox jumps over the lazy dog")
        assertNotNull(expected)
    }

    @Test
    fun should_returnSource_when_inputStreamExists() {
        val expected =
            Converters.inputStreamToSource(
                ByteArrayInputStream("The quick brown fox jumps over the lazy dog".toByteArray(StandardCharsets.UTF_8)),
            )
        assertNotNull(expected)
    }

    @Test
    fun should_throwIOException_when_inputStreamToSourceCalledWithNull() {
        val inputStream =
            object : InputStream() {
                override fun read(): Int = throw IOException("Simulated failure")
            }
        assertThrows(IOException::class.java) { Converters.inputStreamToSource(inputStream) }
    }
}
