package org.opencds.cqf.cql.debug

import org.hl7.elm.r1.Element
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.FunctionDef
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.engine.debug.BreakpointAction
import org.opencds.cqf.cql.engine.debug.BreakpointHandler
import org.opencds.cqf.cql.engine.execution.State
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

class StreamingBreakpointHandler : BreakpointHandler {
    data class CallStackEntry(
        val def: ExpressionDef,
        val callSite: Element?,
        val libraryId: String,
    )

    private val breakpointLines = mutableSetOf<Int>()

    val breakpointsByLibrary = ConcurrentHashMap<String, MutableSet<Int>>()

    val cqlStepLinesByLibrary = ConcurrentHashMap<String, Set<Int>>()

    private val knownLibraryIds = mutableSetOf<String>()

    @Volatile
    var onLibraryEnteredCallback: ((libraryId: String, identifier: VersionedIdentifier?) -> Unit)? = null

    @Volatile
    var primaryLibraryId: String? = null

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
    private var lastPausedElmIdentity: Element? = null

    @Volatile
    var lastPausedState: State? = null
        private set

    private val defineCallStack = ArrayDeque<CallStackEntry>()

    @Volatile
    var lastPausedCallStack: List<CallStackEntry> = emptyList()
        private set

    private var resumeLatch = CountDownLatch(0)

    @Volatile
    private var released = false

    @Volatile
    var onPauseCallback: ((Element, State) -> Unit)? = null

    val runtimeRegistry = RuntimeValueRegistry()

    /** Maps define / stack variable names to their CQL type strings, populated by the server at launch. */
    var variableTypeMap: Map<String, String> = emptyMap()

    /** Full FHIR resources for contexts, keyed by context name (e.g., "Patient"). */
    val contextResourcesByName = ConcurrentHashMap<String, Any?>()

    /** Evaluated results by locator range, for position-based (@line:col) lookup. */
    private val evaluatedValuesByLocator = CopyOnWriteArrayList<Pair<LocatorRange, Any?>>()

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

    enum class StepGranularity {
        CQL,
        AST,
    }

    @Volatile
    var stepGranularity: StepGranularity = StepGranularity.CQL

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
        lastPausedElmIdentity = null
        clearEvaluatedValues()
        resumeLatch.countDown()
    }

    fun stepOver(currentDepth: Int) {
        stepMode = StepMode.STEP_OVER
        depthAtStep = currentDepth
        lastPausedLine = -1
        lastPausedElmIdentity = null
        clearEvaluatedValues()
        resumeLatch.countDown()
    }

    fun stepOut(currentDepth: Int) {
        stepMode = StepMode.STEP_OUT
        depthAtStep = currentDepth
        lastPausedLine = -1
        lastPausedElmIdentity = null
        clearEvaluatedValues()
        resumeLatch.countDown()
    }

    fun resume() {
        stepMode = StepMode.CONTINUE
        lastPausedLine = -1
        lastPausedElmIdentity = null
        clearEvaluatedValues()
        resumeLatch.countDown()
    }

    override fun onBeforeExpression(
        elm: Element,
        state: State,
    ): BreakpointAction {
        val currentLibId = state.getCurrentLibrary()?.identifier?.id
        val isIncludedLibrary = primaryLibraryId != null
            && currentLibId != null
            && currentLibId != primaryLibraryId

        if (isIncludedLibrary && stepMode == StepMode.CONTINUE) {
            val locator = elm.locator ?: return BreakpointAction.CONTINUE
            val line = parseLine(locator) ?: return BreakpointAction.CONTINUE
            if (breakpointsByLibrary[currentLibId]?.contains(line) == true && line != lastPausedLine) {
                log.debug("onBeforeExpression: PAUSE (included library breakpoint) lib={} line={}", currentLibId, line)
                capturePauseState(elm, state, line)
                return BreakpointAction.PAUSE
            }
            return BreakpointAction.CONTINUE
        }

        if (isIncludedLibrary && stepGranularity == StepGranularity.CQL) {
            val locator = elm.locator ?: return BreakpointAction.CONTINUE
            val line = parseLine(locator) ?: return BreakpointAction.CONTINUE
            val depth = state.stack.size
            val cqlFilter = cqlStepLinesByLibrary[currentLibId] ?: cqlStepLinesByLibrary[primaryLibraryId]
            val shouldPause =
                when (stepMode) {
                    StepMode.STEP_IN -> line != lastPausedLine && (cqlFilter == null || line in cqlFilter)
                    StepMode.STEP_OVER -> line != lastPausedLine && depth <= depthAtStep && (cqlFilter == null || line in cqlFilter)
                    StepMode.STEP_OUT -> depth < depthAtStep
                    StepMode.CONTINUE -> line in breakpointsByLibrary[currentLibId].orEmpty() && line != lastPausedLine
                }
            if (shouldPause) {
                capturePauseState(elm, state, line)
                return BreakpointAction.PAUSE
            }
            return BreakpointAction.CONTINUE
        }

        if (isIncludedLibrary && stepGranularity == StepGranularity.AST) {
            val shouldPause =
                when (stepMode) {
                    StepMode.STEP_IN -> elm !== lastPausedElmIdentity
                    StepMode.STEP_OVER -> elm !== lastPausedElmIdentity && state.stack.size <= depthAtStep
                    StepMode.STEP_OUT -> state.stack.size < depthAtStep
                    StepMode.CONTINUE -> {
                        val locator = elm.locator ?: return BreakpointAction.CONTINUE
                        val line = parseLine(locator) ?: return BreakpointAction.CONTINUE
                        line in breakpointsByLibrary[currentLibId].orEmpty() && elm !== lastPausedElmIdentity
                    }
                }
            if (shouldPause) {
                val line = parseLine(elm.locator ?: "") ?: -1
                capturePauseState(elm, state, line)
                return BreakpointAction.PAUSE
            }
            return BreakpointAction.CONTINUE
        }

        // Primary library (or currentLibId is null) — use per-library breakpoints
        val locator = elm.locator ?: return BreakpointAction.CONTINUE
        val line = parseLine(locator) ?: return BreakpointAction.CONTINUE
        val depth = state.stack.size

        val primaryLibLines = if (primaryLibraryId != null) breakpointsByLibrary[primaryLibraryId] else null
        val cqlFilter = cqlStepLines
        val shouldPause =
            when (stepGranularity) {
                StepGranularity.CQL ->
                    when (stepMode) {
                        StepMode.STEP_IN -> line != lastPausedLine && (cqlFilter == null || line in cqlFilter)
                        StepMode.STEP_OVER -> line != lastPausedLine && depth <= depthAtStep && (cqlFilter == null || line in cqlFilter)
                        StepMode.STEP_OUT -> depth < depthAtStep
                        StepMode.CONTINUE -> line in (primaryLibLines ?: breakpointLines) && line != lastPausedLine
                    }
                StepGranularity.AST ->
                    when (stepMode) {
                        StepMode.STEP_IN -> elm !== lastPausedElmIdentity
                        StepMode.STEP_OVER -> elm !== lastPausedElmIdentity && depth <= depthAtStep
                        StepMode.STEP_OUT -> depth < depthAtStep
                        StepMode.CONTINUE -> line in (primaryLibLines ?: breakpointLines) && elm !== lastPausedElmIdentity
                    }
            }

        if (shouldPause) {
            log.debug("onBeforeExpression: PAUSE (primary library breakpoint) line={}", line)
            capturePauseState(elm, state, line)
            return BreakpointAction.PAUSE
        }

        return BreakpointAction.CONTINUE
    }

    override fun onExpressionDefEntered(elm: ExpressionDef, callSite: Element?, state: State) {
        val libId = state.getCurrentLibrary()?.identifier?.id ?: primaryLibraryId ?: ""
        if (libId != primaryLibraryId && libId !in knownLibraryIds) {
            knownLibraryIds.add(libId)
            onLibraryEnteredCallback?.invoke(libId, state.getCurrentLibrary()?.identifier)
        }
        defineCallStack.addLast(CallStackEntry(elm, callSite, libId))
    }

    override fun onExpressionDefEvaluated(
        elm: ExpressionDef,
        state: State,
        value: Any?,
    ) {
        defineCallStack.removeLastOrNull()
        if (elm is FunctionDef) return
        elm.name?.let {
            val defineType = variableTypeMap[it]
            runtimeRegistry.putDefine(it, value, defineType, state.getCurrentLibrary()?.identifier)
        }
    }

    override fun onAfterExpression(
        elm: Element,
        state: State,
        value: Any?,
    ) {
        val locator = elm.locator
        if (locator != null) {
            val range = parseLocatorRange(locator)
            if (range != null) {
                evaluatedValuesByLocator.add(range to value)
            }
        }
    }

    /** Look up an evaluated value by position (0-indexed line and column). */
    fun findValueAtPosition(
        line: Int,
        col: Int,
    ): Any? {
        // Check the last paused expression first
        val pausedElm = lastPausedElm
        val pausedLocator = pausedElm?.locator
        if (pausedLocator != null) {
            val range = parseLocatorRange(pausedLocator)
            if (range != null && (line + 1 to col) in range) {
                // If the paused expression is an ExpressionDef, return its value if available
                if (pausedElm is ExpressionDef) {
                    val rv = runtimeRegistry.find(pausedElm.name ?: "")
                    if (rv != null) return rv.value
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
        evaluatedValuesByLocator.clear()
    }

    private fun capturePauseState(
        elm: Element,
        state: State,
        line: Int,
    ) {
        lastPausedLine = line
        lastPausedElmIdentity = elm
        lastPausedElm = elm
        lastPausedState = state
        lastPausedCallStack = defineCallStack.toList()

        runtimeRegistry.clearStackVariables()

        // Parameters are loaded by CqlDebugServer.onPauseCallback (which has access to
        // parameterMetadata for type info). Nothing to do here.

        // Capture context values — prefer full resource from contextResourcesByName (if pre-populated
        // externally or from a previous capture), fall back to the raw state context value.
        state.contextValues.forEach { (key, value) ->
            if (value is org.hl7.fhir.instance.model.api.IBase) {
                contextResourcesByName[key] = value
            }
        }
        state.contextValues.forEach { (key, value) ->
            val displayValue = contextResourcesByName[key] ?: value
            runtimeRegistry.loadContextResource(key, displayValue, null)
        }

        // Capture stack variables (frame locals, aliases, let clauses)
        for (frame in state.stack) {
            for (v in frame.variables) {
                val name = v.name ?: "(unnamed)"
                runtimeRegistry.putStackVariable(name, v.value, null)
            }
        }

        resumeLatch = CountDownLatch(1)
    }

    fun reset() {
        runtimeRegistry.reset()
        contextResourcesByName.clear()
        evaluatedValuesByLocator.clear()
        breakpointsByLibrary.clear()
        cqlStepLinesByLibrary.clear()
        knownLibraryIds.clear()
        lastPausedLine = -1
        lastPausedElm = null
        lastPausedElmIdentity = null
        lastPausedState = null
        defineCallStack.clear()
        lastPausedCallStack = emptyList()
    }

    companion object {
        private val log = LoggerFactory.getLogger(StreamingBreakpointHandler::class.java)

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
        val t0 = System.nanoTime()
        log.debug("waitForResume: enter released={} [thread={}]", released, Thread.currentThread().name)
        try {
            if (!released) {
                val elm = lastPausedElm
                val state = lastPausedState
                if (elm != null && state != null) {
                    onPauseCallback?.invoke(elm, state)
                    log.debug(
                        "waitForResume: onPauseCallback done [+{}ms] lastPausedElm.localId [{}] lastPausedElm.locator[{}] lastPausedState[{}]",
                        (System.nanoTime() - t0) / 1_000_000,
                        lastPausedElm?.localId,
                        lastPausedElm?.locator,
                        lastPausedState,
                    )
                }
            }
            resumeLatch.await()
            log.debug("waitForResume: latch released [+{}ms]", (System.nanoTime() - t0) / 1_000_000)
        } finally {
            log.debug("waitForResume: exit released={} [thread={}]", released, Thread.currentThread().name)
        }
    }

    override fun release() {
        log.debug("release: setting released=true, counting down latch [thread={}]", Thread.currentThread().name)
        released = true
        resumeLatch.countDown()
    }
}
