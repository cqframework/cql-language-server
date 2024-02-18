package org.opencds.cqf.cql.ls.server.command;

import org.opencds.cqf.cql.ls.server.manager.IgContextManager;
import picocli.CommandLine.Command;

@Command(subcommands = {CqlCommand.class})
public class CliCommand {
    private IgContextManager igContextManager;

    public IgContextManager getIgContextManager() {
        return this.igContextManager;
    }

    public CliCommand(IgContextManager igContextManager) {
        this.igContextManager = igContextManager;
    }
}
