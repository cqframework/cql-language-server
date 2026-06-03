package org.opencds.cqf.cql.ls.server.utility

import org.cqframework.cql.cql2elm.CqlCompiler
import org.cqframework.cql.cql2elm.CqlTranslator
import org.cqframework.cql.cql2elm.tracking.Trackable.resultType
import org.hl7.elm.r1.AliasRef
import org.hl7.elm.r1.AliasedQuerySource
import org.hl7.elm.r1.BinaryExpression
import org.hl7.elm.r1.ByExpression
import org.hl7.elm.r1.Case
import org.hl7.elm.r1.CaseItem
import org.hl7.elm.r1.CodeRef
import org.hl7.elm.r1.CodeSystemRef
import org.hl7.elm.r1.ConceptRef
import org.hl7.elm.r1.Element
import org.hl7.elm.r1.Expression
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.ExpressionRef
import org.hl7.elm.r1.FunctionDef
import org.hl7.elm.r1.FunctionRef
import org.hl7.elm.r1.If
import org.hl7.elm.r1.Instance
import org.hl7.elm.r1.InstanceElement
import org.hl7.elm.r1.LetClause
import org.hl7.elm.r1.Literal
import org.hl7.elm.r1.NaryExpression
import org.hl7.elm.r1.OperandRef
import org.hl7.elm.r1.ParameterRef
import org.hl7.elm.r1.Property
import org.hl7.elm.r1.Query
import org.hl7.elm.r1.RelationshipClause
import org.hl7.elm.r1.Retrieve
import org.hl7.elm.r1.ReturnClause
import org.hl7.elm.r1.Sort
import org.hl7.elm.r1.TernaryExpression
import org.hl7.elm.r1.Tuple
import org.hl7.elm.r1.TupleElement
import org.hl7.elm.r1.UnaryExpression
import org.hl7.elm.r1.ValueSetRef
import org.hl7.elm.r1.With
import org.hl7.elm.r1.Without
import java.util.IdentityHashMap

class ElmAstLibraryWriter(private val compiler: CqlCompiler? = null) {
    private val sb = StringBuilder()
    private val indent = StringBuilder()
    private var isLast = false

    private var currentLine: Int = 0
    private val elementLines = IdentityHashMap<Element, IntRange>()

    data class AstRendering(
        val text: String,
        val elementLines: Map<Element, IntRange>,
    )

    fun render(library: org.hl7.elm.r1.Library): AstRendering {
        sb.clear()
        indent.clear()
        isLast = false
        currentLine = 0
        elementLines.clear()
        renderLibrary(library)
        return AstRendering(sb.toString(), elementLines.toMap())
    }

    fun writeAsString(library: org.hl7.elm.r1.Library): String {
        return render(library).text
    }

    private fun renderLibrary(library: org.hl7.elm.r1.Library) {
        val ident = library.identifier
        val id = ident?.id ?: "unknown"
        val version = ident?.version ?: "unspecified"
        lineNoPrefix("Library: $id (version $version)${idSuffix(library.localId)}")

        val items = mutableListOf<() -> Unit>()

        items.add { line0("translator: CQL-to-ELM ${translatorVersion()}") }

        val schemaId = library.schemaIdentifier
        if (schemaId != null) {
            items.add { line0("schema: ${schemaId.id} ${schemaId.version ?: ""}") }
        }

        val usings = library.usings
        usings?.def?.forEach { using ->
            items.add { line0("using: ${using.localIdentifier} (${using.uri})") }
        }

        val includes = library.includes
        includes?.def?.forEach { inc ->
            val path = inc.path ?: "?"
            val versionInfo = if (inc.version != null) " version ${inc.version}" else ""
            items.add { line0("include: $path$versionInfo") }
        }

        val codeSystems = library.codeSystems
        codeSystems?.def?.forEach { cs ->
            items.add { line0("codesystem: \"${cs.name}\" (${cs.id})") }
        }

        val valueSets = library.valueSets
        valueSets?.def?.forEach { vs ->
            items.add { line0("valueset: \"${vs.name}\" (${vs.id})") }
        }

        val codes = library.codes
        codes?.def?.forEach { code ->
            items.add { line0("code: \"${code.name}\" (${code.id})") }
        }

        val concepts = library.concepts
        concepts?.def?.forEach { concept ->
            items.add { line0("concept: \"${concept.name}\"") }
        }

        val params = library.parameters
        params?.def?.forEach { param ->
            val typeName = param.parameterType?.localPart ?: "?"
            items.add { line0("parameter: \"${param.name}\" $typeName") }
        }

        val contexts = library.contexts
        contexts?.def?.forEach { ctx ->
            items.add { line0("context: ${ctx.name ?: "?"}") }
        }

        val statements = library.statements
        statements?.def?.forEach { stmt ->
            items.add {
                if (stmt is ExpressionDef) {
                    renderExpressionDef(stmt)
                } else if (stmt is FunctionDef) {
                    renderFunctionDef(stmt)
                }
            }
        }

        items.forEachIndexed { i, fn ->
            isLast = i == items.size - 1
            fn()
        }
    }

    private fun renderExpressionDef(def: ExpressionDef) {
        val name = def.name ?: "?"
        val suffix = idSuffix(def.localId, def.locator)
        val typeSuffix = def.resultType?.toString()?.let { " returns ${formatType(it)}" } ?: ""
        nodeLine("define: ${label(name)}$typeSuffix$suffix")
        val startLine = currentLine
        recordElement(def)
        val expr = def.expression
        if (expr != null) {
            pushIndent()
            isLast = true
            renderExpression(expr)
            popIndent()
            recordElementRange(def, startLine)
        }
    }

    private fun renderFunctionDef(def: FunctionDef) {
        val name = def.name ?: "?"
        val suffix = idSuffix(def.localId, def.locator)
        val fluent = if (def.fluent == true) " fluent" else ""
        val operands = def.operand
        val paramsStr =
            operands?.joinToString(", ") { op ->
                val typeName = op.operandType?.localPart ?: "?"
                "${op.name ?: "_"} $typeName"
            } ?: ""
        val typeSuffix = def.resultType?.toString()?.let { " returns ${formatType(it)}" } ?: ""

        nodeLine("define$fluent function: ${label(name)}($paramsStr)$typeSuffix$suffix")
        val startLine = currentLine
        recordElement(def)
        val expr = def.expression
        if (expr != null) {
            pushIndent()
            isLast = true
            renderExpression(expr)
            popIndent()
            recordElementRange(def, startLine)
        }
    }

    private fun renderExpression(expr: Expression) {
        val typeName = expr.javaClass.simpleName
        nodeLine("$typeName${displayValue(expr)}${idSuffix(expr.localId, expr.locator)}")
        val startLine = currentLine
        recordElement(expr)

        val children = childrenOf(expr)
        if (children.isEmpty()) return

        pushIndent()
        children.forEachIndexed { i, child ->
            isLast = i == children.size - 1
            renderChild(child)
        }
        popIndent()
        recordElementRange(expr, startLine)
    }

    private fun renderChild(child: ChildNode) {
        val node = child.node
        val role = child.role
        when {
            node is Expression -> renderExpression(node)
            node is With -> renderWithWithout("With", node, role)
            node is Without -> renderWithWithout("Without", node, role)
            node is AliasedQuerySource -> renderAliasedQuerySource(node, role)
            node is LetClause -> renderLetClause(node, role)
            node is ReturnClause -> renderReturnClause(node, role)
            node is org.hl7.elm.r1.SortClause -> renderSortClause(node, role)
            node is org.hl7.elm.r1.SortByItem -> renderSortByItem(node, role)
            node is TupleElement -> renderTupleElement(node, role)
            node is InstanceElement -> renderInstanceElement(node, role)
            node is CaseItem -> renderCaseItem(node, role)
            else -> {
                if (node is Element) {
                    nodeLine("${node.javaClass.simpleName}${idSuffix(node.localId, node.locator)}")
                    recordElement(node)
                } else {
                    nodeLine("${node.javaClass.simpleName}")
                }
            }
        }
    }

    private fun renderAliasedQuerySource(
        aqs: AliasedQuerySource,
        role: String?,
    ) {
        val alias = aqs.alias ?: "?"
        val rp = if (role != null) "$role: " else ""
        nodeLine("${rp}AliasedQuerySource (alias=${label(alias)})${idSuffix(aqs.localId, aqs.locator)}")
        val startLine = currentLine
        recordElement(aqs)
        val expr = aqs.expression
        if (expr != null) {
            pushIndent()
            isLast = true
            renderExpression(expr)
            popIndent()
            recordElementRange(aqs, startLine)
        }
    }

    private fun renderLetClause(
        lc: LetClause,
        role: String?,
    ) {
        val ident = lc.identifier ?: "?"
        val rp = if (role != null) "$role: " else ""
        nodeLine("${rp}LetClause (identifier=${label(ident)})${idSuffix(lc.localId, lc.locator)}")
        val startLine = currentLine
        recordElement(lc)
        val expr = lc.expression
        if (expr != null) {
            pushIndent()
            isLast = true
            renderExpression(expr)
            popIndent()
            recordElementRange(lc, startLine)
        }
    }

    private fun renderReturnClause(
        rc: ReturnClause,
        role: String?,
    ) {
        val distinct = rc.distinct
        val d = if (distinct != null && distinct) " (distinct)" else ""
        val rp = if (role != null) "$role: " else ""
        nodeLine("${rp}ReturnClause$d${idSuffix(rc.localId, rc.locator)}")
        val startLine = currentLine
        recordElement(rc)
        val expr = rc.expression
        if (expr != null) {
            pushIndent()
            isLast = true
            renderExpression(expr)
            popIndent()
            recordElementRange(rc, startLine)
        }
    }

    private fun renderWithWithout(
        typeName: String,
        rc: Element,
        role: String?,
    ) {
        val rp = if (role != null) "$role: " else ""
        val alias = (rc as? AliasedQuerySource)?.alias
        val aliasStr = if (alias != null) " (alias=${label(alias)})" else ""
        nodeLine("${rp}$typeName$aliasStr${idSuffix(rc.localId, rc.locator)}")
        val startLine = currentLine
        recordElement(rc)

        val children = mutableListOf<ChildNode>()
        val expr = (rc as? AliasedQuerySource)?.expression
        if (expr != null) children.add(ChildNode(expr, "source"))
        val suchThat = (rc as? RelationshipClause)?.suchThat
        if (suchThat != null) children.add(ChildNode(suchThat, "suchThat"))

        if (children.isEmpty()) return

        pushIndent()
        children.forEachIndexed { i, child ->
            isLast = i == children.size - 1
            renderChild(child)
        }
        popIndent()
        recordElementRange(rc, startLine)
    }

    private fun renderSortClause(
        sc: org.hl7.elm.r1.SortClause,
        role: String?,
    ) {
        val rp = if (role != null) "$role: " else ""
        nodeLine("${rp}SortClause${idSuffix(sc.localId, sc.locator)}")
        val startLine = currentLine
        recordElement(sc)
        val by = sc.by
        if (by != null && by.isNotEmpty()) {
            pushIndent()
            by.forEachIndexed { i, item ->
                isLast = i == by.size - 1
                renderSortByItem(item, null)
            }
            popIndent()
            recordElementRange(sc, startLine)
        }
    }

    private fun renderSortByItem(
        item: org.hl7.elm.r1.SortByItem,
        role: String?,
    ) {
        val dir = item.direction
        val direction = if (dir != null) " (${dir.name.lowercase()})" else ""
        nodeLine("SortByItem$direction${idSuffix(item.localId, item.locator)}")
        val startLine = currentLine
        recordElement(item)
        val expr = (item as? ByExpression)?.expression
        if (expr != null) {
            pushIndent()
            isLast = true
            renderExpression(expr)
            popIndent()
            recordElementRange(item, startLine)
        }
    }

    private fun renderTupleElement(
        te: TupleElement,
        role: String?,
    ) {
        val name = te.name ?: "?"
        val rp = if (role != null) "$role: " else ""
        nodeLine("${rp}TupleElement (name=${label(name)})")
        val startLine = currentLine
        val value = te.value
        if (value != null) {
            pushIndent()
            isLast = true
            renderExpression(value)
            popIndent()
        }
    }

    private fun renderInstanceElement(
        ie: InstanceElement,
        role: String?,
    ) {
        val name = ie.name ?: "?"
        val rp = if (role != null) "$role: " else ""
        nodeLine("${rp}InstanceElement (name=${label(name)})")
        val startLine = currentLine
        val value = ie.value
        if (value != null) {
            pushIndent()
            isLast = true
            renderExpression(value)
            popIndent()
        }
    }

    private fun renderCaseItem(
        ci: CaseItem,
        role: String?,
    ) {
        val rp = if (role != null) "$role: " else ""
        nodeLine("${rp}CaseItem${idSuffix(ci.localId, ci.locator)}")
        val startLine = currentLine
        recordElement(ci)
        pushIndent()

        val subItems = mutableListOf<Pair<Element, String?>>()
        val whenExpr = ci.`when`
        if (whenExpr != null) subItems.add(whenExpr to "when")
        val thenExpr = ci.then
        if (thenExpr != null) subItems.add(thenExpr to "then")

        subItems.forEachIndexed { i, (el, r) ->
            isLast = i == subItems.size - 1
            if (el is Expression) {
                nodeLine("$r: ${el.javaClass.simpleName}${displayValue(el)}${idSuffix(el.localId, el.locator)}")
                recordElement(el)
                val children = childrenOf(el)
                if (children.isNotEmpty()) {
                    pushIndent()
                    children.forEachIndexed { ci2, child ->
                        isLast = ci2 == children.size - 1
                        renderChild(child)
                    }
                    popIndent()
                }
            } else {
                nodeLine("$r: ${el.javaClass.simpleName}${idSuffix(el.localId, el.locator)}")
                recordElement(el)
            }
        }
        popIndent()
        recordElementRange(ci, startLine)
    }

    private fun displayValue(expr: Expression): String {
        return when (expr) {
            is Literal -> {
                val v = expr.value
                ": ${v ?: "null"}"
            }
            is ExpressionRef -> {
                val n = expr.name
                " (name=${label(n ?: "?")})"
            }
            is FunctionRef -> {
                val lib = expr.libraryName
                val name = expr.name ?: "?"
                val prefix = if (lib != null) "$lib." else ""
                " ($prefix${label(name)})"
            }
            is ValueSetRef -> " (name=${label(expr.name ?: "?")})"
            is CodeRef -> " (name=${label(expr.name ?: "?")})"
            is CodeSystemRef -> " (name=${label(expr.name ?: "?")})"
            is ConceptRef -> " (name=${label(expr.name ?: "?")})"
            is ParameterRef -> " (name=${label(expr.name ?: "?")})"
            is OperandRef -> " (name=${label(expr.name ?: "?")})"
            is AliasRef -> " (name=${label(expr.name ?: "?")})"
            is Property -> {
                val p = expr.path
                val sc = expr.scope
                val scope = if (sc != null) " scope=$sc" else ""
                " \"${p ?: "?"}\"$scope"
            }
            is Retrieve -> {
                val dt = expr.dataType
                val dataType = dt?.localPart ?: dt?.toString() ?: "?"
                val codeProp = expr.codeProperty?.let { ", codeProperty: $it" } ?: ""
                val codeComp = expr.codeComparator?.let { ", codeComparator: $it" } ?: ""
                val codesSuffix =
                    when (val codes = expr.codes) {
                        is ValueSetRef -> codes.name?.let { ", codes: \"$it\"" } ?: ""
                        is CodeRef -> codes.name?.let { ", codes: \"$it\"" } ?: ""
                        is CodeSystemRef -> codes.name?.let { ", codes: \"$it\"" } ?: ""
                        is org.hl7.elm.r1.ToList -> {
                            val op = codes.operand
                            when (op) {
                                is ValueSetRef -> op.name?.let { ", codes: \"$it\"" } ?: ""
                                is CodeRef -> op.name?.let { ", codes: \"$it\"" } ?: ""
                                is CodeSystemRef -> op.name?.let { ", codes: \"$it\"" } ?: ""
                                else -> ""
                            }
                        }
                        else -> ""
                    }
                " (dataType: $dataType$codeProp$codeComp$codesSuffix)"
            }
            is org.hl7.elm.r1.Quantity -> {
                val v = expr.value
                val u = expr.unit
                val value = v?.toPlainString() ?: "?"
                val unit = if (u != null && u.isNotEmpty()) " '$u'" else ""
                ": $value$unit"
            }
            is org.hl7.elm.r1.Null -> ": null"
            else -> ""
        }
    }

    private fun childrenOf(expr: Expression): List<ChildNode> {
        return when (expr) {
            is Query -> queryChildren(expr)
            is Sort -> sortChildren(expr)
            is Case -> caseChildren(expr)
            is If -> ifChildren(expr)
            is Tuple -> tupleChildren(expr)
            is Instance -> instanceChildren(expr)
            is org.hl7.elm.r1.List -> expr.element?.map { ChildNode(it, "element") } ?: emptyList()
            is org.hl7.elm.r1.Interval -> intervalChildren(expr)
            is org.hl7.elm.r1.Slice -> sliceChildren(expr)
            is org.hl7.elm.r1.Repeat -> repeatChildren(expr)
            is org.hl7.elm.r1.Combine -> singleSourceChild(expr.source)
            is org.hl7.elm.r1.AggregateExpression -> singleSourceChild(expr.source)
            is org.hl7.elm.r1.Last -> singleSourceChild(expr.source)
            is org.hl7.elm.r1.First -> singleSourceChild(expr.source)
            is org.hl7.elm.r1.Collapse -> operandListChild(expr.operand)
            is org.hl7.elm.r1.Expand -> operandListChild(expr.operand)
            is org.hl7.elm.r1.Message -> singleSourceChild(expr.source)
            is org.hl7.elm.r1.Aggregate -> singleSourceChild(expr.source)
            is Property -> singleSourceChild(expr.source)
            is FunctionRef -> operandListChild(expr.operand)
            is UnaryExpression -> singleOperandChild(expr.operand)
            is BinaryExpression -> operandListChild(expr.operand)
            is TernaryExpression -> operandListChild(expr.operand)
            is NaryExpression -> operandListChild(expr.operand)
            is org.hl7.elm.r1.ToList -> singleOperandChild(expr.operand)
            is org.hl7.elm.r1.As -> singleOperandChild(expr.operand)
            is org.hl7.elm.r1.Is -> singleOperandChild(expr.operand)
            is org.hl7.elm.r1.ToConcept -> singleOperandChild(expr.operand)
            is org.hl7.elm.r1.In -> operandListChild(expr.operand)
            is org.hl7.elm.r1.Contains -> operandListChild(expr.operand)
            is org.hl7.elm.r1.Exists -> singleOperandChild(expr.operand)
            is Retrieve -> retrieveChildren(expr)
            else -> emptyList()
        }
    }

    private fun tupleChildren(t: Tuple): List<ChildNode> {
        return t.element?.map { ChildNode(it, "element") } ?: emptyList()
    }

    private fun instanceChildren(i: Instance): List<ChildNode> {
        return i.element?.map { ChildNode(it, "element") } ?: emptyList()
    }

    private fun queryChildren(q: Query): List<ChildNode> {
        val list = mutableListOf<ChildNode>()
        q.source.forEach { list.add(ChildNode(it, "source")) }
        val let = q.let
        if (let != null) let.forEach { list.add(ChildNode(it, "let")) }
        q.relationship.forEach { list.add(ChildNode(it, "relationship")) }
        val whereExpr = q.where
        if (whereExpr != null) list.add(ChildNode(whereExpr, "where"))
        val returnClause = q.`return`
        if (returnClause != null) list.add(ChildNode(returnClause, "return"))
        val sort = q.sort
        if (sort != null) list.add(ChildNode(sort, "sort"))
        return list
    }

    private fun sortChildren(s: Sort): List<ChildNode> {
        val list = mutableListOf<ChildNode>()
        val src = s.source
        if (src != null) list.add(ChildNode(src, "source"))
        val by = s.by
        if (by != null) by.forEach { list.add(ChildNode(it, "by")) }
        return list
    }

    private fun caseChildren(c: Case): List<ChildNode> {
        val list = mutableListOf<ChildNode>()
        val comp = c.comparand
        if (comp != null) list.add(ChildNode(comp, "comparand"))
        c.caseItem.forEach { list.add(ChildNode(it, "caseItem")) }
        val elseExpr = c.`else`
        if (elseExpr != null) list.add(ChildNode(elseExpr, "else"))
        return list
    }

    private fun ifChildren(ifExpr: If): List<ChildNode> {
        val list = mutableListOf<ChildNode>()
        val cond = ifExpr.condition
        if (cond != null) list.add(ChildNode(cond, "condition"))
        val thenExpr = ifExpr.then
        if (thenExpr != null) list.add(ChildNode(thenExpr, "then"))
        val elseExpr = ifExpr.`else`
        if (elseExpr != null) list.add(ChildNode(elseExpr, "else"))
        return list
    }

    private fun intervalChildren(iv: org.hl7.elm.r1.Interval): List<ChildNode> {
        val list = mutableListOf<ChildNode>()
        val low = iv.low
        if (low != null) list.add(ChildNode(low, "low"))
        val high = iv.high
        if (high != null) list.add(ChildNode(high, "high"))
        return list
    }

    private fun sliceChildren(s: org.hl7.elm.r1.Slice): List<ChildNode> {
        val list = mutableListOf<ChildNode>()
        val src = s.source
        if (src != null) list.add(ChildNode(src, "source"))
        val start = s.startIndex
        if (start != null) list.add(ChildNode(start, "startIndex"))
        val end = s.endIndex
        if (end != null) list.add(ChildNode(end, "endIndex"))
        return list
    }

    private fun repeatChildren(r: org.hl7.elm.r1.Repeat): List<ChildNode> {
        val list = mutableListOf<ChildNode>()
        val src = r.source
        if (src != null) list.add(ChildNode(src, "source"))
        val el = r.element
        if (el != null) list.add(ChildNode(el, "element"))
        return list
    }

    private fun retrieveChildren(r: Retrieve): List<ChildNode> {
        val list = mutableListOf<ChildNode>()
        val codes = r.codes
        if (codes != null) list.add(ChildNode(codes, "codes"))
        return list
    }

    private fun singleSourceChild(source: Expression?): List<ChildNode> {
        return if (source != null) listOf(ChildNode(source, "source")) else emptyList()
    }

    private fun singleOperandChild(operand: Expression?): List<ChildNode> {
        return if (operand != null) listOf(ChildNode(operand, "operand")) else emptyList()
    }

    private fun operandListChild(operands: kotlin.collections.List<Expression>?): List<ChildNode> {
        return operands?.map { ChildNode(it, "operand") } ?: emptyList()
    }

    private data class ChildNode(
        val node: Any,
        val role: String?,
    )

    private fun translatorVersion(): String {
        return CqlTranslator::class.java.`package`.implementationVersion ?: "?"
    }

    private fun label(name: String): String =
        if (name.startsWith("\"")) name else "\"$name\""

    private fun formatType(raw: String): String =
        raw.replace("list<", "List<")
            .replace("interval<", "Interval<")
            .replace("tuple{", "Tuple{")
            .replace("choice<", "Choice<")

    private fun idSuffix(
        localId: String?,
        locator: String?,
    ): String {
        return when {
            localId != null && locator != null -> " [id=$localId, loc=$locator]"
            localId != null -> " [id=$localId]"
            locator != null -> " [loc=$locator]"
            else -> ""
        }
    }

    private fun idSuffix(localId: String?): String =
        if (localId != null) " [id=$localId]" else ""

    private fun pushIndent() {
        indent.append("  ")
    }

    private fun popIndent() {
        indent.setLength((indent.length - 2).coerceAtLeast(0))
    }

    private fun recordElement(e: Element) {
        elementLines[e] = currentLine..currentLine
    }

    private fun recordElementRange(
        e: Element,
        startLine: Int,
    ) {
        elementLines[e] = startLine..currentLine
    }

    private fun line0(text: String) {
        currentLine++
        sb.append(indent)
        sb.append(if (isLast) "└── " else "├── ")
        sb.append(text)
        sb.append('\n')
    }

    private fun lineNoPrefix(text: String) {
        currentLine++
        sb.append(text)
        sb.append('\n')
    }

    private fun nodeLine(text: String) {
        currentLine++
        sb.append(indent)
        sb.append(if (isLast) "└── " else "├── ")
        sb.append(text)
        sb.append('\n')
    }
}
