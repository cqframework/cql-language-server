package org.opencds.cqf.cql.ls.server.utility

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VersionReaderTest {
    @Test
    fun loadVersion_cqlToElmJvm_returnsVersion() {
        val version = VersionReader.loadVersion("cql-to-elm-jvm")
        assertNotNull(version, "Expected version from cql-to-elm-jvm artifact")
    }

    @Test
    fun loadVersion_nonExistent_returnsNull() {
        val version = VersionReader.loadVersion("non-existent-artifact-id-xyz")
        assertNull(version, "Expected null for non-existent artifact")
    }
}
