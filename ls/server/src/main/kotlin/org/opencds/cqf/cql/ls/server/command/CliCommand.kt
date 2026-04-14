package org.opencds.cqf.cql.ls.server.command

import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import picocli.CommandLine.Command

@Command(subcommands = [CqlCommand::class])
class CliCommand(val igContextManager: IgContextManager)
