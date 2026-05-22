package org.opencds.cqf.cql.ls.server.provider

import org.cqframework.cql.cql2elm.model.CompiledLibrary
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.hl7.elm.r1.CodeRef
import org.hl7.elm.r1.CodeSystemRef
import org.hl7.elm.r1.ConceptRef
import org.hl7.elm.r1.Element
import org.hl7.elm.r1.ExpressionRef
import org.hl7.elm.r1.FunctionDef
import org.hl7.elm.r1.FunctionRef
import org.hl7.elm.r1.IncludeDef
import org.hl7.elm.r1.Library
import org.hl7.elm.r1.Literal
import org.hl7.elm.r1.OperandRef
import org.hl7.elm.r1.ParameterRef
import org.hl7.elm.r1.ValueSetRef
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager
import org.opencds.cqf.cql.ls.server.utility.TrackBacks
import org.opencds.cqf.cql.ls.server.visitor.DefinitionTrackBackVisitor
import org.slf4j.LoggerFactory
import java.net.URI

class DefinitionProvider(
    private val compilationManager: CqlCompilationManager,
    private val contentService: ContentService,
) {
    companion object {
        private val log = LoggerFactory.getLogger(DefinitionProvider::class.java)
    }

    fun definition(params: DefinitionParams): List<LocationLink> {
        val uri = Uris.parseOrNull(params.textDocument.uri) ?: return emptyList()
        val compiler = compilationManager.compile(uri) ?: return emptyList()
        val library = compiler.library ?: return emptyList()
        val compiledLibrary = compiler.compiledLibrary ?: return emptyList()
        val elm = DefinitionTrackBackVisitor().visitLibrary(library, params.position) ?: return emptyList()
        return resolveDefinition(elm, uri, library, compiledLibrary)
    }

    private fun resolveDefinition(
        refElm: Element,
        uri: URI,
        library: Library,
        compiledLibrary: CompiledLibrary,
    ): List<LocationLink> {
        val root = Uris.getHead(uri)
        return when (refElm) {
            is FunctionRef -> {
                val name = refElm.name ?: return emptyList()
                val libAlias = refElm.libraryName
                if (libAlias == null) {
                    val defs = compiledLibrary.resolveFunctionRef(name)
                    defs.mapNotNull { locationLinkFromElements(refElm, it, uri) }
                } else {
                    val targetUri = resolveLibraryAlias(libAlias, library, root) ?: return emptyList()
                    val targetCompiled = compilationManager.compile(targetUri) ?: return emptyList()
                    val targetCompiledLib = targetCompiled.compiledLibrary ?: return emptyList()
                    targetCompiledLib.resolveFunctionRef(name).mapNotNull { locationLinkFromElements(refElm, it, targetUri) }
                }
            }
            is ExpressionRef -> {
                val name = refElm.name ?: return emptyList()
                val libAlias = refElm.libraryName
                if (libAlias == null) {
                    val def = compiledLibrary.resolveExpressionRef(name) ?: return emptyList()
                    listOfNotNull(locationLinkFromElements(refElm, def, uri))
                } else {
                    val targetUri = resolveLibraryAlias(libAlias, library, root) ?: return emptyList()
                    val targetCompiled = compilationManager.compile(targetUri) ?: return emptyList()
                    val targetCompiledLib = targetCompiled.compiledLibrary ?: return emptyList()
                    val def = targetCompiledLib.resolveExpressionRef(name) ?: return emptyList()
                    listOfNotNull(locationLinkFromElements(refElm, def, targetUri))
                }
            }
            is ValueSetRef -> {
                val name = refElm.name ?: return emptyList()
                val libAlias = refElm.libraryName
                if (libAlias == null) {
                    val def = compiledLibrary.resolveValueSetRef(name) ?: return emptyList()
                    listOfNotNull(locationLinkFromElements(refElm, def, uri))
                } else {
                    val targetUri = resolveLibraryAlias(libAlias, library, root) ?: return emptyList()
                    val targetCompiled = compilationManager.compile(targetUri) ?: return emptyList()
                    val targetCompiledLib = targetCompiled.compiledLibrary ?: return emptyList()
                    val def = targetCompiledLib.resolveValueSetRef(name) ?: return emptyList()
                    listOfNotNull(locationLinkFromElements(refElm, def, targetUri))
                }
            }
            is CodeRef -> {
                val name = refElm.name ?: return emptyList()
                val libAlias = refElm.libraryName
                if (libAlias == null) {
                    val def = compiledLibrary.resolveCodeRef(name) ?: return emptyList()
                    listOfNotNull(locationLinkFromElements(refElm, def, uri))
                } else {
                    val targetUri = resolveLibraryAlias(libAlias, library, root) ?: return emptyList()
                    val targetCompiled = compilationManager.compile(targetUri) ?: return emptyList()
                    val targetCompiledLib = targetCompiled.compiledLibrary ?: return emptyList()
                    val def = targetCompiledLib.resolveCodeRef(name) ?: return emptyList()
                    listOfNotNull(locationLinkFromElements(refElm, def, targetUri))
                }
            }
            is ConceptRef -> {
                val name = refElm.name ?: return emptyList()
                val libAlias = refElm.libraryName
                if (libAlias == null) {
                    val def = compiledLibrary.resolveConceptRef(name) ?: return emptyList()
                    listOfNotNull(locationLinkFromElements(refElm, def, uri))
                } else {
                    val targetUri = resolveLibraryAlias(libAlias, library, root) ?: return emptyList()
                    val targetCompiled = compilationManager.compile(targetUri) ?: return emptyList()
                    val targetCompiledLib = targetCompiled.compiledLibrary ?: return emptyList()
                    val def = targetCompiledLib.resolveConceptRef(name) ?: return emptyList()
                    listOfNotNull(locationLinkFromElements(refElm, def, targetUri))
                }
            }
            is CodeSystemRef -> {
                val name = refElm.name ?: return emptyList()
                val libAlias = refElm.libraryName
                if (libAlias == null) {
                    val def = compiledLibrary.resolveCodeSystemRef(name) ?: return emptyList()
                    listOfNotNull(locationLinkFromElements(refElm, def, uri))
                } else {
                    val targetUri = resolveLibraryAlias(libAlias, library, root) ?: return emptyList()
                    val targetCompiled = compilationManager.compile(targetUri) ?: return emptyList()
                    val targetCompiledLib = targetCompiled.compiledLibrary ?: return emptyList()
                    val def = targetCompiledLib.resolveCodeSystemRef(name) ?: return emptyList()
                    listOfNotNull(locationLinkFromElements(refElm, def, targetUri))
                }
            }
            is IncludeDef -> {
                // Navigate to the top of the included library file
                val identifier =
                    VersionedIdentifier().apply {
                        id = refElm.path ?: return emptyList()
                        version = refElm.version
                    }
                val top = Range(Position(0, 0), Position(0, 0))
                val originRange = refElm.locator?.let { TrackBacks.toRange(it) }
                contentService.locate(root, identifier).map { targetUri ->
                    LocationLink().apply {
                        originSelectionRange = originRange
                        this.targetUri = targetUri.toString()
                        targetRange = top
                        targetSelectionRange = top
                    }
                }
            }
            is ParameterRef -> {
                // Parameters are library-scoped; resolve locally only
                val name = refElm.name ?: return emptyList()
                val def = library.parameters?.def?.firstOrNull { it.name == name } ?: return emptyList()
                listOfNotNull(locationLinkFromElements(refElm, def, uri))
            }
            is OperandRef -> {
                // Operands are local to a function; search all function definitions
                val name = refElm.name ?: return emptyList()
                val funcDef =
                    library.statements?.def
                        ?.filterIsInstance<FunctionDef>()
                        ?.firstOrNull { it.operand?.any { od -> od.name == name } == true } ?: return emptyList()
                val def = funcDef.operand?.first { it.name == name }
                // OperandDef may not have a locator from the compiler; fall back to the
                // FunctionDef locator so the user navigates to the function signature.
                val target = if (def?.locator != null) def else funcDef
                listOfNotNull(locationLinkFromElements(refElm, target, uri))
            }
            is Literal -> {
                // Literals have no definition to navigate to
                emptyList()
            }
            else -> emptyList()
        }
    }

    /**
     * Resolves a library alias (e.g., "FL") to the URI of the included library.
     */
    private fun resolveLibraryAlias(
        alias: String,
        library: Library,
        root: URI,
    ): URI? {
        val includeDef =
            library.includes?.def?.firstOrNull { it.localIdentifier == alias } ?: run {
                log.debug("definition: no IncludeDef found for alias '{}'", alias)
                return null
            }
        val identifier =
            VersionedIdentifier().apply {
                id = includeDef.path ?: return null
                version = includeDef.version
            }
        return contentService.locate(root, identifier).firstOrNull()
    }

    private fun locationLinkFromElements(
        refElm: Element,
        defElm: Element,
        uri: URI,
    ): LocationLink? {
        val targetRange = defElm.locator?.let { TrackBacks.toRange(it) } ?: return null
        return LocationLink().apply {
            originSelectionRange = refElm.locator?.let { TrackBacks.toRange(it) }
            targetUri = uri.toString()
            this.targetRange = targetRange
            targetSelectionRange = targetRange
        }
    }
}
