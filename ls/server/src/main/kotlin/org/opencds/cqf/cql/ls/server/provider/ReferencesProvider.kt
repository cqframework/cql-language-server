package org.opencds.cqf.cql.ls.server.provider

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.ReferenceParams
import org.hl7.elm.r1.CodeRef
import org.hl7.elm.r1.CodeSystemRef
import org.hl7.elm.r1.ConceptRef
import org.hl7.elm.r1.Element
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.ExpressionRef
import org.hl7.elm.r1.IncludeDef
import org.hl7.elm.r1.Library
import org.hl7.elm.r1.ValueSetRef
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.utility.ElmIdentifiers
import org.opencds.cqf.cql.ls.server.visitor.AllReferencesVisitor
import org.opencds.cqf.cql.ls.server.visitor.DefinitionTrackBackVisitor
import org.opencds.cqf.cql.ls.server.visitor.ExpressionTrackBackVisitor
import org.opencds.cqf.cql.ls.server.visitor.LibraryAliasReferencesVisitor
import java.net.URI

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

        // Include line: find every alias.XXX call site in this file and sibling files.
        if (elm is IncludeDef) {
            return includeDefReferences(elm, uri, library)
        }

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

    private fun includeDefReferences(
        includeDef: IncludeDef,
        uri: URI,
        library: Library,
    ): List<Location> {
        val alias = includeDef.localIdentifier ?: return emptyList()
        val results = mutableListOf<Location>()

        // All alias.XXX usages in the current file
        results += LibraryAliasReferencesVisitor(uri).visitLibrary(library, alias)

        // Other files that include the same library — each may use a different alias
        val libIdentifier = ElmIdentifiers.fromIncludeDef(includeDef) ?: return results
        for (depUri in compilationManager.getDependentUris(libIdentifier)) {
            if (depUri == uri) continue
            val depLibrary = compilationManager.compile(depUri)?.library ?: continue
            val depInclude =
                depLibrary.includes?.def?.firstOrNull { inc ->
                    val incId = ElmIdentifiers.fromIncludeDef(inc) ?: return@firstOrNull false
                    incId.id == libIdentifier.id && incId.system == libIdentifier.system
                } ?: continue
            val depAlias = depInclude.localIdentifier ?: continue
            results += LibraryAliasReferencesVisitor(depUri).visitLibrary(depLibrary, depAlias)
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
