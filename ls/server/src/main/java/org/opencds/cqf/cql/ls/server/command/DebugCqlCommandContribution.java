package org.opencds.cqf.cql.ls.server.command;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.opencds.cqf.cql.evaluator.cli.Main;
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution;
import com.google.gson.JsonElement;

// TODO: This will be moved to the debug plugin once that's more fully baked..
public class DebugCqlCommandContribution implements CommandContribution {

    // TODO: Delete once the plugin is fully supported
    public static final String START_DEBUG_COMMAND =
            "org.opencds.cqf.cql.ls.plugin.debug.startDebugSession";

    private CompletableFuture<Object> executeCql(ExecuteCommandParams params) {
        try {
            List<String> arguments = params.getArguments().stream().map(JsonElement.class::cast)
                    .map(JsonElement::getAsString).collect(Collectors.toList());

            // Temporarily redirect std out, because uh... I didn't do that very smart.
            ByteArrayOutputStream baosOut = new ByteArrayOutputStream();
            System.setOut(new PrintStream(baosOut));

            ByteArrayOutputStream baosErr = new ByteArrayOutputStream();
            System.setErr(new PrintStream(baosErr));

            Main.run(arguments.toArray(new String[arguments.size()]));

            String out = baosOut.toString();
            String err = baosErr.toString();

            if (err.length() > 0) {
                out += "\nEvaluation logs:\n";
                out += err;
            }

            return CompletableFuture.completedFuture(out);
        } finally {
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out)));
            System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err)));
        }
    }

    @Override
    public Set<String> getCommands() {
        return Collections.singleton(START_DEBUG_COMMAND);
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        if (START_DEBUG_COMMAND.equals(params.getCommand())) {
            return this.executeCql(params);
        } else {
            return CommandContribution.super.executeCommand(params);
        }
    }
}
