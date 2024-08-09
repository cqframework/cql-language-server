package org.opencds.cqf.cql.ls.server.command;

import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

import org.apache.commons.lang3.SystemUtils;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager;
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager;
import org.opencds.cqf.cql.ls.server.manager.IgContextManager;
import org.opencds.cqf.cql.ls.server.service.TestContentService;

import static org.junit.jupiter.api.Assertions.*;

class DebugCqlCommandContributionTest {

    private static final String FILE_UNC_PREFIX = "file:///";

    private static DebugCqlCommandContribution contribution;

    @BeforeAll
    static void beforeAll() {
        ContentService cs = new TestContentService();
        contribution = new DebugCqlCommandContribution(new IgContextManager((cs)));
    }

    @Test
    void getCommands() {
        assertEquals(1, contribution.getCommands().size());
        assertEquals(
                "org.opencds.cqf.cql.ls.plugin.debug.startDebugSession",
                contribution.getCommands().toArray()[0]);
    }

    @Test
    void withOnlyLibrary() {
        ExecuteCommandParams params = new ExecuteCommandParams();

        String libraryPath = normalizePath("file://" + System.getProperty("user.dir") + "/src/test/resources/org/opencds/cqf/cql/ls/server/");

        params.setCommand("org.opencds.cqf.cql.ls.plugin.debug.startDebugSession");
        params.setArguments(Arrays.asList(
                "cql",
                "-fv=R4",
                "-m=FHIR",
                "-ln=One",
                "-lu=" + libraryPath
        ).stream().map(str -> "\"" + str + "\"").map(JsonParser::parseString).collect(Collectors.toList()));
        Object result = contribution.executeCommand(params).join();
        assertInstanceOf(String.class, result);
        assertTrue(((String) result).trim().endsWith("One=1"));
    }

    @Test
    void withTerminology() {
        ExecuteCommandParams params = new ExecuteCommandParams();

        String libraryPath = normalizePath("file://" + System.getProperty("user.dir") + "/src/test/resources/org/opencds/cqf/cql/ls/server/");

        params.setCommand("org.opencds.cqf.cql.ls.plugin.debug.startDebugSession");
        params.setArguments(Arrays.asList(
                "cql",
                "-fv=R4",
                "-m=FHIR",
                "-ln=One",
                "-lu=" + libraryPath,
                "-t=" + libraryPath
        ).stream().map(str -> "\"" + str + "\"").map(JsonParser::parseString).collect(Collectors.toList()));
        Object result = contribution.executeCommand(params).join();
        assertInstanceOf(String.class, result);
        assertTrue(((String) result).trim().endsWith("One=1"));
    }

    @Test
    void withFileModel() {
        ExecuteCommandParams params = new ExecuteCommandParams();

        String modelPath = normalizePath("file://" + System.getProperty("user.dir") + "/src/test/resources/org/opencds/cqf/cql/ls/server/");
        String libraryPath = normalizePath("file://" + System.getProperty("user.dir") + "/src/test/resources/org/opencds/cqf/cql/ls/server/");

        params.setCommand("org.opencds.cqf.cql.ls.plugin.debug.startDebugSession");
        params.setArguments(Arrays.asList(
                "cql",
                "-fv=R4",
                "-m=FHIR",
                "-mu=" + modelPath,
                "-ln=One",
                "-lu=" + libraryPath,
                "-t=" + libraryPath
        ).stream().map(str -> "\"" + str + "\"").map(JsonParser::parseString).collect(Collectors.toList()));
        Object result = contribution.executeCommand(params).join();
        assertInstanceOf(String.class, result);
        assertTrue(((String) result).trim().endsWith("One=1"));
    }

//    @Test
//    void withRemoteModel() {
//        ExecuteCommandParams params = new ExecuteCommandParams();
//
//        String modelPath = "http://localhost:8000";
//        String libraryPath = normalizePath("file://" + System.getProperty("user.dir") + "/src/test/resources/org/opencds/cqf/cql/ls/server/");
//
//        params.setCommand("org.opencds.cqf.cql.ls.plugin.debug.startDebugSession");
//        params.setArguments(Arrays.asList(
//                "cql",
//                "-fv=R4",
//                "-m=FHIR",
//                "-mu=" + modelPath,
//                "-ln=One",
//                "-lu=" + libraryPath,
//                "-t=" + libraryPath
//        ).stream().map(str -> "\"" + str + "\"").map(JsonParser::parseString).collect(Collectors.toList()));
//        Object result = contribution.executeCommand(params).join();
//        assertInstanceOf(String.class, result);
//        assertTrue(((String) result).trim().endsWith("One=1"));
//    }

    @Test
    void withRootDir() {
        ExecuteCommandParams params = new ExecuteCommandParams();

        String libraryPath = normalizePath("file://" + System.getProperty("user.dir") + "/src/test/resources/org/opencds/cqf/cql/ls/server/");

        params.setCommand("org.opencds.cqf.cql.ls.plugin.debug.startDebugSession");
        params.setArguments(Arrays.asList(
                "cql",
                "-fv=R4",
                "-m=FHIR",
                "--root-dir=" + libraryPath,
                "-ln=One",
                "-lu=" + libraryPath
        ).stream().map(str -> "\"" + str + "\"").map(JsonParser::parseString).collect(Collectors.toList()));
        Object result = contribution.executeCommand(params).join();
        assertInstanceOf(String.class, result);
        assertTrue(((String) result).trim().endsWith("One=1"));
    }

    private static String normalizePath(String path) {
        if(SystemUtils.IS_OS_WINDOWS && path.startsWith(FILE_UNC_PREFIX)) {
            try {
                URI uri = new URI(path);
                return new File(uri.getSchemeSpecificPart()).toURI().getRawPath();
            } catch(Exception e) {
                return path;
            }
        }

        return path;
    }
}
