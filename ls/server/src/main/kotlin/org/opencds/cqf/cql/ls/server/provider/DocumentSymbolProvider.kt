package org.opencds.cqf.cql.ls.server.provider

import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.hl7.elm.r1.CodeDef
import org.hl7.elm.r1.CodeSystemDef
import org.hl7.elm.r1.ConceptDef
import org.hl7.elm.r1.Element
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.FunctionDef
import org.hl7.elm.r1.ParameterDef
import org.hl7.elm.r1.ValueSetDef
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.utility.TrackBacks

class DocumentSymbolProvider(
    private val compilationManager: CqlCompilationManager,
) {
    fun documentSymbol(params: DocumentSymbolParams): List<DocumentSymbol> {
        val uri = Uris.parseOrNull(params.textDocument.uri) ?: return emptyList()
        val library = compilationManager.compile(uri)?.library ?: return emptyList()
        val result = mutableListOf<DocumentSymbol>()

        library.parameters?.def?.forEach { result.addIfMapped(it, SymbolKind.Constant) }
        library.codeSystems?.def?.forEach { result.addIfMapped(it, SymbolKind.Module) }
        library.valueSets?.def?.forEach { result.addIfMapped(it, SymbolKind.Enum) }
        library.codes?.def?.forEach { result.addIfMapped(it, SymbolKind.EnumMember) }
        library.concepts?.def?.forEach { result.addIfMapped(it, SymbolKind.Struct) }
        library.statements?.def?.forEach { def ->
            val kind = if (def is FunctionDef) SymbolKind.Function else SymbolKind.Variable
            result.addIfMapped(def, kind)
        }

        return result
    }

    private fun MutableList<DocumentSymbol>.addIfMapped(
        elm: Element,
        kind: SymbolKind,
    ) {
        val name = nameOf(elm) ?: return
        val range = elm.locator?.let { TrackBacks.toRange(it) } ?: ZERO_RANGE
        add(DocumentSymbol(name, kind, range, range))
    }

    private fun nameOf(elm: Element): String? =
        when (elm) {
            is ExpressionDef -> elm.name
            is ParameterDef -> elm.name
            is CodeSystemDef -> elm.name
            is ValueSetDef -> elm.name
            is CodeDef -> elm.name
            is ConceptDef -> elm.name
            else -> null
        }

    companion object {
        private val ZERO_RANGE = Range(Position(0, 0), Position(0, 0))
    }
}
