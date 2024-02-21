package org.opencds.cqf.cql.ls.server.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.server.manager.CompilerOptionsManager;
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager;
import org.opencds.cqf.cql.ls.server.manager.IgContextManager;
import org.opencds.cqf.cql.ls.server.service.TestContentService;

class ViewElmCommandContributionTest {

    private static ViewElmCommandContribution viewElmCommandContribution;

    @BeforeAll
    static void beforeAll() {
        ContentService cs = new TestContentService();
        CqlCompilationManager cqlCompilationManager =
                new CqlCompilationManager(cs, new CompilerOptionsManager(cs), new IgContextManager(cs));
        viewElmCommandContribution = new ViewElmCommandContribution(cqlCompilationManager);
    }

    @Test
    void getCommands() {
        assertEquals(1, viewElmCommandContribution.getCommands().size());
        assertEquals(
                "org.opencds.cqf.cql.ls.viewElm",
                viewElmCommandContribution.getCommands().toArray()[0]);
    }

    @Test
    void executeCommand() {
        ExecuteCommandParams params = new ExecuteCommandParams();
        params.setCommand("org.opencds.cqf.cql.ls.viewElm");
        params.setArguments(Collections.singletonList(
                JsonParser.parseString("\"\\/org\\/opencds\\/cqf\\/cql\\/ls\\/server\\/One.cql\"")));
        viewElmCommandContribution.executeCommand(params).thenAccept(result -> {
            try {
                String expectedXml = new String(Files.readAllBytes(Paths.get("src/test/resources/One.xml")))
                        .trim()
                        .replaceAll("\\s+", "");
                assertEquals(expectedXml, result.toString().trim().replaceAll("\\s+", ""));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
