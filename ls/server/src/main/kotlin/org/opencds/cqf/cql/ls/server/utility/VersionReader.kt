package org.opencds.cqf.cql.ls.server.utility

import java.util.Properties

object VersionReader {
    private val versions: Properties by lazy {
        val props = Properties()
        this::class.java.getResourceAsStream("/versions.properties")?.use { props.load(it) }
        props
    }

    fun loadVersion(artifactId: String): String? =
        versions.getProperty("$artifactId.version")
}
