package org.opencds.cqf.cql.ls.server.provider

import org.cqframework.cql.tools.formatter.CqlFormatterVisitor
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris

class FormattingProvider(private val contentService: ContentService) {
    fun format(uri: String): List<TextEdit> {
        val u = requireNotNull(Uris.parseOrNull(uri))

        val fr =
            try {
                CqlFormatterVisitor.getFormattedOutput(
                    contentService.read(u) ?: throw IllegalArgumentException("Unable to read content from: $u"),
                )
            } catch (e: Exception) {
                throw IllegalArgumentException("Unable to format CQL due to an error.", e)
            }

        if (fr.errors.isNotEmpty()) {
            throw IllegalArgumentException(
                listOf("Unable to format CQL due to syntax errors.", "Please fix the errors and try again.").joinToString("\n"),
            )
        }

        val te =
            TextEdit(
                Range(Position(0, 0), Position(Int.MAX_VALUE, Int.MAX_VALUE)),
                fr.output,
            )

        return listOf(te)
    }
}
