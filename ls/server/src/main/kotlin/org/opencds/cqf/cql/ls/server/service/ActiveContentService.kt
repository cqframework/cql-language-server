package org.opencds.cqf.cql.ls.server.service

import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.greenrobot.eventbus.Subscribe
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.event.DidChangeTextDocumentEvent
import org.opencds.cqf.cql.ls.server.event.DidCloseTextDocumentEvent
import org.opencds.cqf.cql.ls.server.event.DidOpenTextDocumentEvent
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.StringReader
import java.io.StringWriter
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class ActiveContentService : ContentService {
    data class VersionedContent(val content: String, val version: Int)

    private val activeContent = ConcurrentHashMap<URI, VersionedContent>()

    override fun locate(
        root: URI,
        identifier: VersionedIdentifier,
    ): Set<URI> {
        requireNotNull(root)
        requireNotNull(identifier)
        return searchActiveContent(root, identifier)
    }

    override fun read(
        root: URI,
        identifier: VersionedIdentifier,
    ): InputStream? {
        requireNotNull(root)
        requireNotNull(identifier)
        val uris = locate(root, identifier)
        check(uris.size == 1) { "Found more than one file for identifier: $identifier" }
        return read(uris.first())
    }

    override fun read(uri: URI): InputStream? {
        requireNotNull(uri)
        val content = activeContent[uri]?.content ?: return null
        return ByteArrayInputStream(content.toByteArray())
    }

    @Subscribe(priority = 100)
    fun didOpen(e: DidOpenTextDocumentEvent) {
        val document: TextDocumentItem = e.params().textDocument
        val uri = Uris.parseOrNull(document.uri) ?: return
        activeContent[uri] = VersionedContent(document.text, document.version)
    }

    @Subscribe(priority = 100)
    fun didClose(e: DidCloseTextDocumentEvent) {
        val document: TextDocumentIdentifier = e.params().textDocument
        val uri = Uris.parseOrNull(document.uri) ?: return
        activeContent.remove(uri)
    }

    @Subscribe(priority = 100)
    fun didChange(e: DidChangeTextDocumentEvent) {
        val document: VersionedTextDocumentIdentifier = e.params().textDocument
        val uri = Uris.parseOrNull(document.uri) ?: return
        val existing = activeContent[uri] ?: return
        val existingText = existing.content

        if (document.version > existing.version) {
            for (change in e.params().contentChanges) {
                if (change.range == null) {
                    activeContent[uri] = VersionedContent(change.text, document.version)
                } else {
                    val newText = patch(existingText, change)
                    activeContent[uri] = VersionedContent(newText, document.version)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    internal fun patch(
        sourceText: String,
        change: TextDocumentContentChangeEvent,
    ): String {
        val range = change.range
        val reader = BufferedReader(StringReader(sourceText))
        val writer = StringWriter()

        repeat(range.start.line) { writer.write(reader.readLine() + '\n') }
        repeat(range.start.character) { writer.write(reader.read()) }

        writer.write(change.text)

        reader.skip(change.rangeLength.toLong())

        generateSequence { reader.read().takeIf { it != -1 } }
            .forEach { writer.write(it) }
        return writer.toString()
    }

    internal fun searchActiveContent(
        root: URI,
        identifier: VersionedIdentifier,
    ): Set<URI> {
        val id = identifier.id
        val version = identifier.version
        val matchText =
            "(?s).*library\\s+$id" +
                if (version != null) "\\s+version\\s+'$version'\\s+(?s).*" else "'\\s+(?s).*"
        val pattern = matchText.toRegex()
        return activeContent
            .filterKeys { root.relativize(it) != it }
            .filterValues { it.content.matches(pattern) }
            .keys.toSet()
    }

    fun activeUris(): Set<URI> = activeContent.keys
}
