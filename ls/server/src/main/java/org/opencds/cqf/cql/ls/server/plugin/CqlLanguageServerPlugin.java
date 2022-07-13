package org.opencds.cqf.cql.ls.server.plugin;

public interface CqlLanguageServerPlugin {
    String getName();
    CommandContribution getCommandContribution();
}