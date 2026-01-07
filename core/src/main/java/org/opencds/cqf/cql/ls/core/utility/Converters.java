package org.opencds.cqf.cql.ls.core.utility;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import kotlinx.io.Buffer;
import kotlinx.io.Source;

public class Converters {

    public static String inputStreamToString(InputStream inputStream) throws IOException {
        StringBuilder resultStringBuilder = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!resultStringBuilder.isEmpty()) resultStringBuilder.append("\n"); // Append newline if needed
                resultStringBuilder.append(line);
            }
        }
        return resultStringBuilder.toString();
    }

    public static Source stringToSource(String text) {
        Buffer buffer = new Buffer();
        // Write the string to the buffer using a specific character encoding
        buffer.write(text.getBytes(), 0, text.length());
        // Return the buffer as a Source
        return buffer;
    }

    public static Source inputStreamToSource(InputStream inputStream) throws IOException {
        return stringToSource(inputStreamToString(inputStream));
    }
}
