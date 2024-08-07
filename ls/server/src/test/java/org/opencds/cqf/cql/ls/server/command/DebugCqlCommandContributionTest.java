package org.opencds.cqf.cql.ls.server.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.ExecuteCommandParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager;
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager;
import org.opencds.cqf.cql.ls.server.manager.IgContextManager;
import org.opencds.cqf.cql.ls.server.service.TestContentService;

class DebugCqlCommandContributionTest {

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
    void executeCommand() {
        ExecuteCommandParams params = new ExecuteCommandParams();
        System.out.println(System.getProperty("user.dir"));
        params.setCommand("org.opencds.cqf.cql.ls.plugin.debug.startDebugSession");
        params.setArguments(Arrays.asList(
                "cql",
                "-fv=R4",
                "-m=FHIR",
                "-mu=/org/opencds/cqf/cql/ls/server/One.cql",
                "-ln=One",
                "-lu=file://" + System.getProperty("user.dir") + "/src/test/resources/org/opencds/cqf/cql/ls/server/",
                "-t=file://" + System.getProperty("user.dir") + "/src/test/resources/org/opencds/cqf/cql/ls/server/"
        ).stream().map(str -> "\"" + str + "\"").map(JsonParser::parseString).collect(Collectors.toList()));
        contribution.executeCommand(params).thenAccept(result -> {
            assertEquals("One=1", result.toString());
        });
    }
}
