package org.opencds.cqf.cql.ls.core.utility;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class ConvertersTest {

    @Test
    void should_returnString_when_inputStreamExists() {
        var expected = "The quick brown fox jumps over the lazy dog";
        try {
            var actual =
                    Converters.inputStreamToString(new ByteArrayInputStream(expected.getBytes(StandardCharsets.UTF_8)));
            assertEquals(actual, expected);
        } catch (IOException e) {
            fail("Unexpected exception thrown. {}", e);
        }
    }

    @Test
    void should_returnStringWithLineBreaks_when_inputStreamHasLineBreaksExists() {
        var expected = "the first day in spring –\n" + "a wind from the ocean\n" + "but no ocean in sight";
        try {
            var actual =
                    Converters.inputStreamToString(new ByteArrayInputStream(expected.getBytes(StandardCharsets.UTF_8)));
            assertEquals(actual, expected);
        } catch (IOException e) {
            fail("Unexpected exception thrown. {}", e);
        }
    }

    @Test
    void should_throwIOException_when_inputStreamToStringHasAnIOError() throws IOException {
        InputStream inputStream = mock(InputStream.class);
        when(inputStream.read()).thenThrow(new IOException("Simulated failure"));
        assertThrows(IOException.class, () -> Converters.inputStreamToString(inputStream));
    }

    @Test
    void should_returnSource_when_stringExists() {
        var expected = Converters.stringToSource("The quick brown fox jumps over the lazy dog");
        assertNotNull(expected);
    }

    @Test
    void should_returnSource_when_inputStreamExists() {
        try {
            var expected = Converters.inputStreamToSource(new ByteArrayInputStream(
                    "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8)));
            assertNotNull(expected);
        } catch (IOException e) {
            fail("Unexpected exception thrown. {}", e);
        }
    }

    @Test
    void should_throwIOException_when_inputStreamToSourceCalledWithNull() throws IOException {
        InputStream inputStream = mock(InputStream.class);
        when(inputStream.read()).thenThrow(new IOException("Simulated failure"));
        assertThrows(IOException.class, () -> Converters.inputStreamToSource(inputStream));
    }
}
