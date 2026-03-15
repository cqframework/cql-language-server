package org.opencds.cqf.cql.ls.server.plugin

interface CqlLanguageServerPlugin {
    fun getName(): String

    fun getCommandContribution(): CommandContribution?
}
