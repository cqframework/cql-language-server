package org.opencds.cqf.cql.ls.plugin;

public interface CqlLanguageServerPlugin {
    String getName();
    CommandContribution getCommandContribution();
}