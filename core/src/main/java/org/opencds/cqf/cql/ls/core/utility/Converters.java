package org.opencds.cqf.cql.ls.core.utility;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import kotlinx.io.Buffer;
import kotlinx.io.Source;
import kotlinx.io.files.Path;

public class Converters {

    public static String inputStreamToString(InputStream inputStream) throws IOException {
        StringBuilder resultStringBuilder = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                resultStringBuilder.append(line).append("\n"); // Append newline if needed
            }
        }
        return resultStringBuilder.toString();
    }

    public static java.nio.file.Path kotlinPathToJavaPath(Path kotlinPath) {
        if (kotlinPath == null) {
            return null;
        }
        // Paths.get() expects a String representing the path
        return Paths.get(kotlinPath.toString());
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
