package org.opencds.cqf.cql.ls.server.provider

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.ReferenceParams
import org.hl7.elm.r1.CodeRef
import org.hl7.elm.r1.CodeSystemRef
import org.hl7.elm.r1.ConceptRef
import org.hl7.elm.r1.Element
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.ExpressionRef
import org.hl7.elm.r1.ValueSetRef
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.visitor.AllReferencesVisitor
import org.opencds.cqf.cql.ls.server.visitor.DefinitionTrackBackVisitor
import org.opencds.cqf.cql.ls.server.visitor.ExpressionTrackBackVisitor

class ReferencesProvider(
    private val compilationManager: CqlCompilationManager,
    @Suppress("unused") private val contentService: ContentService,
) {
    fun references(params: ReferenceParams): List<Location> {
        val uri = Uris.parseOrNull(params.textDocument.uri) ?: return emptyList()
        val compiler = compilationManager.compile(uri) ?: return emptyList()
        val library = compiler.library ?: return emptyList()

        // When cursor is on a reference (ExpressionRef, ValueSetRef, etc.) use DefinitionTrackBackVisitor.
        // When cursor is on a definition (ExpressionDef, FunctionDef) use ExpressionTrackBackVisitor.
        val elm: Element =
            DefinitionTrackBackVisitor().visitLibrary(library, params.position)
                ?: ExpressionTrackBackVisitor().visitLibrary(library, params.position)
                ?: return emptyList()

        val symbolName = nameOf(elm) ?: return emptyList()

        val results = mutableListOf<Location>()

        // Search within this library first
        results += AllReferencesVisitor(uri).visitLibrary(library, symbolName)

        // Then search every library that directly includes this one
        val identifier = library.identifier ?: return results
        val dependentUris = compilationManager.getDependentUris(identifier)
        for (depUri in dependentUris) {
            val depCompiler = compilationManager.compile(depUri) ?: continue
            val depLibrary = depCompiler.library ?: continue
            results += AllReferencesVisitor(depUri).visitLibrary(depLibrary, symbolName)
        }

        return results
    }

    private fun nameOf(elm: Element): String? =
        when (elm) {
            is ExpressionDef -> elm.name // also covers FunctionDef
            is ExpressionRef -> elm.name // also covers FunctionRef
            is ValueSetRef -> elm.name
            is CodeRef -> elm.name
            is ConceptRef -> elm.name
            is CodeSystemRef -> elm.name
            else -> null
        }
}
