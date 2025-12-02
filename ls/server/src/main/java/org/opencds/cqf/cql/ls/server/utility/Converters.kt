package org.opencds.cqf.cql.ls.server.utility

import kotlinx.io.Source
import kotlinx.io.asSource

import java.io.InputStream
import java.nio.file.Paths

fun convertInputStreamToSource(inputStream: InputStream) : Source = inputStream.asSource() as Source

fun convertKotlinPathToJavaPath(kotlinPath: kotlinx.io.files.Path?) : java.nio.file.Path? =
    kotlinPath?.let { Paths.get(it.toString()) }