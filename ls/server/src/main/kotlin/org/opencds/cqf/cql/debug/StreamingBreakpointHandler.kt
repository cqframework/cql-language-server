package org.opencds.cqf.cql.debug

import org.hl7.elm.r1.Element
import org.hl7.elm.r1.ExpressionDef
import org.opencds.cqf.cql.engine.debug.BreakpointAction
import org.opencds.cqf.cql.engine.debug.BreakpointHandler
import org.opencds.cqf.cql.engine.execution.State
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

class StreamingBreakpointHandler : BreakpointHandler {
    private val breakpointLines = mutableSetOf<Int>()

    @Volatile
    var cqlStepLines: Set<Int>? = null

    @Volatile
    private var stepMode: StepMode = StepMode.CONTINUE

    @Volatile
    private var depthAtStep: Int = 0

    private var lastPausedLine: Int = -1

    @Volatile
    var lastPausedElm: Element? = null
        private set

    @Volatile
    var lastPausedState: State? = null
        private set

    private var resumeLatch = CountDownLatch(0)

    @Volatile
    var onPauseCallback: ((Element, State) -> Unit)? = null

    /** ExpressionDef results from completed evaluations, keyed by define name. */
    val evaluatedValuesByName = ConcurrentHashMap<String, Any?>()

    /** Full FHIR resources for contexts, keyed by context name (e.g., "Patient"). */
    val contextResourcesByName = ConcurrentHashMap<String, Any?>()

    /** Evaluated results by locator range, for position-based (@line:col) lookup. */
    private val evaluatedValuesByLocator = mutableListOf<Pair<LocatorRange, Any?>>()

    data class LocatorRange(
        val startLine: Int,
        val startChar: Int,
        val endLine: Int,
        val endChar: Int,
    ) {
        operator fun contains(position: Pair<Int, Int>): Boolean {
            val (line, col) = position
            if (line < startLine || line > endLine) return false
            if (line == startLine && col < startChar) return false
            if (line == endLine && col > endChar) return false
            return true
        }
    }

    enum class StepMode {
        STEP_IN,
        STEP_OVER,
        STEP_OUT,
        CONTINUE,
    }

    fun getStepMode(): StepMode = stepMode

    fun getBreakpointLines(): Set<Int> = breakpointLines.toSet()

    fun setBreakpoints(lines: Set<Int>) {
        breakpointLines.clear()
        breakpointLines.addAll(lines)
    }

    fun applyCqlStepLineFilter(lines: Set<Int>) {
        cqlStepLines = lines
    }

    fun stepIn() {
        stepMode = StepMode.STEP_IN
        lastPausedLine = -1
        clearEvaluatedValues()
        resumeLatch.countDown()
    }

    fun stepOver(currentDepth: Int) {
        stepMode = StepMode.STEP_OVER
        depthAtStep = currentDepth
        lastPausedLine = -1
        clearEvaluatedValues()
        resumeLatch.countDown()
    }

    fun stepOut(currentDepth: Int) {
        stepMode = StepMode.STEP_OUT
        depthAtStep = currentDepth
        clearEvaluatedValues()
        resumeLatch.countDown()
    }

    fun continue_() {
        stepMode = StepMode.CONTINUE
        clearEvaluatedValues()
        resumeLatch.countDown()
    }

    override fun onBeforeExpression(elm: Element, state: State): BreakpointAction {
        val locator = elm.locator ?: return BreakpointAction.CONTINUE
        val line = parseLine(locator) ?: return BreakpointAction.CONTINUE
        val depth = state.stack.size

        val cqlFilter = cqlStepLines
        val shouldPause =
            when (stepMode) {
                StepMode.STEP_IN -> line != lastPausedLine && (cqlFilter == null || line in cqlFilter)
                StepMode.STEP_OVER -> line != lastPausedLine && depth <= depthAtStep && (cqlFilter == null || line in cqlFilter)
                StepMode.STEP_OUT -> depth < depthAtStep
                StepMode.CONTINUE -> line in breakpointLines && line != lastPausedLine
            }

        if (shouldPause) {
            lastPausedLine = line
            lastPausedElm = elm
            lastPausedState = state
            
            // Capture full context resources
            state.contextValues.forEach { (key, value) ->
                if (value is org.hl7.fhir.instance.model.api.IBase) {
                    contextResourcesByName[key] = value
                }
            }

            resumeLatch = CountDownLatch(1)
            return BreakpointAction.PAUSE
        }

        return BreakpointAction.CONTINUE
    }

    override fun onAfterExpression(elm: Element, state: State, value: Any?) {
        if (elm is ExpressionDef) {
            elm.name?.let { evaluatedValuesByName[it] = value }
        }
        val locator = elm.locator
        if (locator != null) {
            val range = parseLocatorRange(locator)
            if (range != null) {
                evaluatedValuesByLocator.add(range to value)
            }
        }
    }

    /** Look up an evaluated value by position (0-indexed line and column). */
    fun findValueAtPosition(line: Int, col: Int): Any? {
        // Check the last paused expression first
        val pausedElm = lastPausedElm
        val pausedLocator = pausedElm?.locator
        if (pausedLocator != null) {
            val range = parseLocatorRange(pausedLocator)
            if (range != null && (line + 1 to col) in range) {
                // If the paused expression is an ExpressionDef, return its value if available
                if (pausedElm is ExpressionDef) {
                    return evaluatedValuesByName[pausedElm.name]
                }
            }
        }
        // Search stored results by locator
        for ((range, value) in evaluatedValuesByLocator.asReversed()) {
            if ((line + 1 to col) in range) {
                return value
            }
        }
        return null
    }

    /** Return the name of the paused expression, or null. */
    fun getPausedExpressionName(): String? {
        val elm = lastPausedElm
        if (elm is ExpressionDef) {
            return elm.name
        }
        return elm?.javaClass?.simpleName
    }
    
    fun getContextResource(contextName: String): Any? {
        return contextResourcesByName[contextName]
    }

    private fun clearEvaluatedValues() {
        evaluatedValuesByName.clear()
        contextResourcesByName.clear()
        evaluatedValuesByLocator.clear()
    }

    companion object {
        fun parseLine(locator: String): Int? {
            return locator.substringBefore(":").toIntOrNull()
        }

        fun parseLocatorRange(locator: String): LocatorRange? {
            // Format: "startLine:startChar-endLine:endChar" or "startLine:startChar"
            val parts = locator.split("-")
            if (parts.size == 2) {
                val start = parts[0].split(":")
                val end = parts[1].split(":")
                if (start.size == 2 && end.size == 2) {
                    val sl = start[0].toIntOrNull() ?: return null
                    val sc = start[1].toIntOrNull() ?: return null
                    val el = end[0].toIntOrNull() ?: return null
                    val ec = end[1].toIntOrNull() ?: return null
                    return LocatorRange(sl, sc, el, ec)
                }
            }
            if (parts.size == 1) {
                val start = parts[0].split(":")
                if (start.size == 2) {
                    val sl = start[0].toIntOrNull() ?: return null
                    val sc = start[1].toIntOrNull() ?: return null
                    return LocatorRange(sl, sc, sl, sc)
                }
            }
            return null
        }
    }

    override fun waitForResume() {
        val elm = lastPausedElm
        val state = lastPausedState
        if (elm != null && state != null) {
            onPauseCallback?.invoke(elm, state)
        }
        resumeLatch.await()
    }

    override fun release() {
        resumeLatch.countDown()
    }

}
