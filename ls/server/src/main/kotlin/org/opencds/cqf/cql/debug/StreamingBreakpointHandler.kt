package org.opencds.cqf.cql.debug

import org.cqframework.cql.cql2elm.LibraryManager
import org.hl7.elm.r1.Element
import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.FunctionDef
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.engine.debug.BreakpointAction
import org.opencds.cqf.cql.engine.debug.BreakpointHandler
import org.opencds.cqf.cql.engine.elm.executing.FunctionRefEvaluator
import org.opencds.cqf.cql.engine.execution.EvaluationVisitor
import org.opencds.cqf.cql.engine.execution.State
import org.opencds.cqf.cql.engine.runtime.Value
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentHashMap.newKeySet
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class StreamingBreakpointHandler(
    val runtimeRegistry: RuntimeValueRegistry = RuntimeValueRegistry(),
) : BreakpointHandler {
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

    /**
     * The [LibraryManager] actually used to execute this debug session (set by
     * [org.opencds.cqf.cql.ls.server.command.CqlEvaluator] right after it builds the engine).
     * This is a DIFFERENT, always-fresh instance from the LSP's cached `CqlCompilationManager`
     * compiler — the only one guaranteed to have successfully resolved every library (including
     * npm-installed / bundled ones like FHIRHelpers) actually used by THIS run, so it's what the
     * debug server should query for source text of libraries with no workspace file.
     */
    @Volatile
    var libraryManager: LibraryManager? = null

    @Volatile
    var primaryLibraryId: String? = null

    @Volatile
    var cqlStepLines: Set<Int>? = null

    @Volatile
    private var stepMode: StepMode = StepMode.CONTINUE

    @Volatile
    private var depthAtStep: Int = 0

    private var lastPausedLine: Int = -1

    /**
     * Library id the last pause occurred in, paired with [lastPausedLine] for CQL-granularity
     * dedup. Without this, a pause at the same line number in a DIFFERENT library (a common
     * coincidence across small CQL functions) is treated as "already paused here" and silently
     * skipped — see the `line != lastPausedLine` comparisons below, all of which also check this.
     */
    private var lastPausedLibId: String? = null

    @Volatile
    var lastPausedElm: Element? = null
        internal set

    @Volatile
    private var lastPausedElmIdentity: Element? = null

    /**
     * Tracks [Element.localId] (qualified by library ID) of every ELM node
     * paused on during the current step session. Prevents re-pausing when the
     * engine re-enters the same ELM node across patient rows (e.g.,
     * [FHIRHelpers.ToString] once per patient).
     *
     * The compound key is "$libId:$localId". If [localId] is null (defensive
     * fallback), the key is "hash:<identityHashCode>".
     *
     * The set is cleared ONLY on [resume]/[prepareResume]/[reset], NOT on every
     * [prepareStep]. This means a visited node is invisible to stepping for
     * the remainder of the run — the only way to re-pause on it is to press
     * Resume (clearing the set) and re-enter via a breakpoint.
     */
    private val visitedElmKeysInStepSession = newKeySet<String>()

    private fun elmKey(
        elm: Element,
        libId: String?,
    ): String {
        val id = elm.localId ?: return "hash:${System.identityHashCode(elm)}"
        return "${libId ?: ""}:$id"
    }

    @Volatile
    var lastPausedState: State? = null
        internal set

    private val defineCallStack = ArrayDeque<CallStackEntry>()

    @Volatile
    var lastPausedCallStack: List<CallStackEntry> = emptyList()
        internal set

    @Volatile
    private var resumeLatch = CountDownLatch(0)

    @Volatile
    private var released = false

    @Volatile
    var onPauseCallback: ((Element, State) -> Unit)? = null

    /** Maps define / stack variable names to their CQL type strings, populated by the server at launch. */
    var variableTypeMap: Map<String, String> = emptyMap()

    /** Full FHIR resources for contexts, keyed by context name (e.g., "Patient"). */
    val contextResourcesByName = ConcurrentHashMap<String, Any?>()

    /**
     * A one-shot ad-hoc evaluation request submitted by the DAP `evaluate()` thread and
     * consumed by the parked engine thread inside [waitForResume]. The result is delivered
     * asynchronously via [resultFuture].
     */
    data class AdHocEvalRequest(
        val functionDef: FunctionDef,
        val argumentValues: List<Value?>,
        val resultFuture: CompletableFuture<Value?>,
    )

    /** Single-slot hand-off from the DAP evaluate() thread to the parked engine thread. */
    private val pendingEval = AtomicReference<AdHocEvalRequest?>()

    /**
     * A no-op [BreakpointHandler] swapped in for the duration of an ad-hoc evaluation to
     * prevent re-entrant breakpoint pausing. Only [onBeforeExpression] is abstract on the
     * interface; every other method already has a default no-op body.
     */
    private object ContinueOnlyBreakpointHandler : BreakpointHandler {
        override fun onBeforeExpression(
            elm: org.hl7.elm.r1.Element,
            state: State,
        ): BreakpointAction = BreakpointAction.CONTINUE
    }

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

    private fun prepareStep() {
        log.debug(
            "prepareStep: stepMode={} stepGranularity={} clearing lastPausedElmIdentity (was={}@{} line={})",
            stepMode,
            stepGranularity,
            lastPausedElmIdentity?.javaClass?.simpleName,
            lastPausedElmIdentity?.localId,
            lastPausedLine,
        )
        released = false
        lastPausedLine = -1
        lastPausedLibId = null
        lastPausedElmIdentity = null
        clearEvaluatedValues()
    }

    fun stepIn() {
        prepareStep()
        stepMode = StepMode.STEP_IN
        resumeLatch.countDown()
    }

    fun stepOver(currentDepth: Int) {
        prepareStep()
        stepMode = StepMode.STEP_OVER
        depthAtStep = currentDepth
        resumeLatch.countDown()
    }

    fun stepOut(currentDepth: Int) {
        prepareStep()
        stepMode = StepMode.STEP_OUT
        depthAtStep = currentDepth
        resumeLatch.countDown()
    }

    fun resume() {
        prepareStep()
        visitedElmKeysInStepSession.clear()
        stepMode = StepMode.CONTINUE
        resumeLatch.countDown()
    }

    fun resumeFromPause() {
        resumeLatch.countDown()
    }

    fun prepareStepIn() {
        prepareStep()
        stepMode = StepMode.STEP_IN
    }

    fun prepareStepOver(currentDepth: Int) {
        prepareStep()
        stepMode = StepMode.STEP_OVER
        depthAtStep = currentDepth
    }

    fun prepareStepOut(currentDepth: Int) {
        prepareStep()
        stepMode = StepMode.STEP_OUT
        depthAtStep = currentDepth
    }

    fun prepareResume() {
        prepareStep()
        visitedElmKeysInStepSession.clear()
        stepMode = StepMode.CONTINUE
    }

    override fun onBeforeExpression(
        elm: Element,
        state: State,
    ): BreakpointAction {
        if (released) return BreakpointAction.CONTINUE
        val currentLibId = state.getCurrentLibrary()?.identifier?.id
        val isIncludedLibrary =
            primaryLibraryId != null &&
                currentLibId != null &&
                currentLibId != primaryLibraryId
        log.debug(
            "onBeforeExpression: ENTER elmClass={} locator={} currentLibId={} isIncludedLibrary={} stepMode={} stepGranularity={} lastPausedElmIdentity={}",
            elm.javaClass.simpleName,
            elm.locator,
            currentLibId,
            isIncludedLibrary,
            stepMode,
            stepGranularity,
            lastPausedElmIdentity?.let { "${it.javaClass.simpleName}@${it.localId ?: "hash:${System.identityHashCode(it)}"}" },
        )

        if (isIncludedLibrary && stepMode == StepMode.CONTINUE) {
            val locator = elm.locator ?: return BreakpointAction.CONTINUE
            val line = parseLine(locator) ?: return BreakpointAction.CONTINUE
            val libBreakpoints = breakpointsByLibrary[currentLibId]
            if (libBreakpoints?.contains(line) == true && (line != lastPausedLine || currentLibId != lastPausedLibId)) {
                log.debug("onBeforeExpression: PAUSE (included library breakpoint) lib={} line={}", currentLibId, line)
                capturePauseState(elm, state, line)
                return BreakpointAction.PAUSE
            }
            log.debug(
                "onBeforeExpression: SKIP (included library CONTINUE) lib={} line={} libBreakpoints={} lastPausedLine={} hasBreakpoint={}",
                currentLibId,
                line,
                libBreakpoints,
                lastPausedLine,
                libBreakpoints?.contains(line),
            )
            return BreakpointAction.CONTINUE
        }

        if (isIncludedLibrary && stepGranularity == StepGranularity.CQL) {
            val locator = elm.locator ?: return BreakpointAction.CONTINUE
            val line = parseLine(locator) ?: return BreakpointAction.CONTINUE
            val depth = state.stack.size
            // Falling back to the PRIMARY library's breakpointable-line set here would be wrong —
            // it's a different file with unrelated line numbers, so it would filter out every
            // real line in `currentLibId` and silently skip pausing anywhere in it. If this
            // library's own line set isn't loaded yet, treat it as "no filter" instead.
            val cqlFilter = cqlStepLinesByLibrary[currentLibId]
            val pausedHere = line == lastPausedLine && currentLibId == lastPausedLibId
            val stepInCondition = !pausedHere && (cqlFilter == null || line in cqlFilter)
            val stepOverCondition = !pausedHere && depth <= depthAtStep && (cqlFilter == null || line in cqlFilter)
            val stepOutCondition = depth < depthAtStep
            val continueCondition = line in breakpointsByLibrary[currentLibId].orEmpty() && !pausedHere
            val shouldPause =
                when (stepMode) {
                    StepMode.STEP_IN -> stepInCondition
                    StepMode.STEP_OVER -> stepOverCondition
                    StepMode.STEP_OUT -> stepOutCondition
                    StepMode.CONTINUE -> continueCondition
                }
            log.debug(
                "onBeforeExpression: EVAL (included library CQL) lib={} line={} stepMode={} depth={} depthAtStep={} " +
                    "cqlFilterSize={} lastPausedLine={} stepInCond={} stepOverCond={} stepOutCond={} continueCond={} shouldPause={}",
                currentLibId,
                line,
                stepMode,
                depth,
                depthAtStep,
                cqlFilter?.size,
                lastPausedLine,
                stepInCondition,
                stepOverCondition,
                stepOutCondition,
                continueCondition,
                shouldPause,
            )
            if (shouldPause) {
                log.debug("onBeforeExpression: PAUSE (included library CQL) lib={} line={}", currentLibId, line)
                capturePauseState(elm, state, line)
                return BreakpointAction.PAUSE
            }
            return BreakpointAction.CONTINUE
        }

        if (isIncludedLibrary && stepGranularity == StepGranularity.AST) {
            val locator = elm.locator ?: return BreakpointAction.CONTINUE
            val line = parseLine(locator) ?: return BreakpointAction.CONTINUE
            val elmNotVisited = elmKey(elm, currentLibId) !in visitedElmKeysInStepSession
            val stackSize = state.stack.size
            val stepInCondition = elmNotVisited
            val stepOverCondition = elmNotVisited && stackSize <= depthAtStep
            val stepOutCondition = stackSize < depthAtStep
            val continueCondition = line in breakpointsByLibrary[currentLibId].orEmpty() && elmNotVisited
            val shouldPause =
                when (stepMode) {
                    StepMode.STEP_IN -> stepInCondition
                    StepMode.STEP_OVER -> stepOverCondition
                    StepMode.STEP_OUT -> stepOutCondition
                    StepMode.CONTINUE -> continueCondition
                }
            log.debug(
                "onBeforeExpression: EVAL (included library AST) lib={} line={} stepMode={} " +
                    "elmKey={} elmNotVisited={} stackSize={} depthAtStep={} " +
                    "stepInCond={} stepOverCond={} stepOutCond={} continueCond={} shouldPause={}",
                currentLibId,
                line,
                stepMode,
                elmKey(elm, currentLibId),
                elmNotVisited,
                stackSize,
                depthAtStep,
                stepInCondition,
                stepOverCondition,
                stepOutCondition,
                continueCondition,
                shouldPause,
            )
            if (shouldPause) {
                log.debug("onBeforeExpression: PAUSE (included library AST) lib={} line={}", currentLibId, line)
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
        val elmNotVisited = elmKey(elm, currentLibId) !in visitedElmKeysInStepSession
        val pausedHere = line == lastPausedLine && currentLibId == lastPausedLibId
        val cqlStepInCondition = !pausedHere && (cqlFilter == null || line in cqlFilter)
        val cqlStepOverCondition = !pausedHere && depth <= depthAtStep && (cqlFilter == null || line in cqlFilter)
        val cqlStepOutCondition = depth < depthAtStep
        val cqlContinueCondition = line in (primaryLibLines ?: breakpointLines) && !pausedHere
        val astStepInCondition = elmNotVisited
        val astStepOverCondition = elmNotVisited && depth <= depthAtStep
        val astStepOutCondition = depth < depthAtStep
        val astContinueCondition = line in (primaryLibLines ?: breakpointLines) && elmNotVisited
        val shouldPause =
            when (stepGranularity) {
                StepGranularity.CQL ->
                    when (stepMode) {
                        StepMode.STEP_IN -> cqlStepInCondition
                        StepMode.STEP_OVER -> cqlStepOverCondition
                        StepMode.STEP_OUT -> cqlStepOutCondition
                        StepMode.CONTINUE -> cqlContinueCondition
                    }
                StepGranularity.AST ->
                    when (stepMode) {
                        StepMode.STEP_IN -> astStepInCondition
                        StepMode.STEP_OVER -> astStepOverCondition
                        StepMode.STEP_OUT -> astStepOutCondition
                        StepMode.CONTINUE -> astContinueCondition
                    }
            }

        log.debug(
            "onBeforeExpression: EVAL (primary library) lib={} line={} stepMode={} stepGran={} depth={} depthAtStep={} " +
                "cqlFilterSize={} lastPausedLine={} elmKey={} elmNotVisited={} " +
                "cqlStepIn={} cqlStepOver={} cqlStepOut={} cqlContinue={} " +
                "astStepIn={} astStepOver={} astStepOut={} astContinue={} shouldPause={}",
            currentLibId,
            line,
            stepMode,
            stepGranularity,
            depth,
            depthAtStep,
            cqlFilter?.size,
            lastPausedLine,
            elmKey(elm, currentLibId),
            elmNotVisited,
            cqlStepInCondition,
            cqlStepOverCondition,
            cqlStepOutCondition,
            cqlContinueCondition,
            astStepInCondition,
            astStepOverCondition,
            astStepOutCondition,
            astContinueCondition,
            shouldPause,
        )

        if (shouldPause) {
            log.debug("onBeforeExpression: PAUSE (primary library) line={}", line)
            capturePauseState(elm, state, line)
            return BreakpointAction.PAUSE
        }

        return BreakpointAction.CONTINUE
    }

    override fun onExpressionDefEntered(
        elm: ExpressionDef,
        callSite: Element?,
        state: State,
    ) {
        val libId = state.getCurrentLibrary()?.identifier?.id ?: primaryLibraryId ?: ""
        if (libId != primaryLibraryId && libId !in knownLibraryIds) {
            log.debug(
                "onExpressionDefEntered: NEW LIBRARY libId={} elm={} primaryLibId={} firing callback",
                libId,
                elm.name,
                primaryLibraryId,
            )
            knownLibraryIds.add(libId)
            onLibraryEnteredCallback?.invoke(libId, state.getCurrentLibrary()?.identifier)
        }
        defineCallStack.addLast(CallStackEntry(elm, callSite, libId))
        log.debug(
            "onExpressionDefEntered: PUSH libId={} elm={} stackDepth={}",
            libId,
            elm.name,
            defineCallStack.size,
        )
    }

    override fun onExpressionDefEvaluated(
        elm: ExpressionDef,
        state: State,
        value: org.opencds.cqf.cql.engine.runtime.Value?,
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
        value: org.opencds.cqf.cql.engine.runtime.Value?,
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
        val libId = state.getCurrentLibrary()?.identifier?.id
        log.debug(
            "capturePauseState: line={} elmClass={} elmKey={} libId={} stepMode={} stepGran={}",
            line,
            elm.javaClass.simpleName,
            elmKey(elm, libId),
            libId,
            stepMode,
            stepGranularity,
        )
        lastPausedLine = line
        lastPausedLibId = libId
        lastPausedElmIdentity = elm
        lastPausedElm = elm
        visitedElmKeysInStepSession.add(elmKey(elm, libId))
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
        released = false
        resumeLatch = CountDownLatch(0)
        pendingEval.set(null)
        runtimeRegistry.reset()
        contextResourcesByName.clear()
        evaluatedValuesByLocator.clear()
        breakpointsByLibrary.clear()
        cqlStepLinesByLibrary.clear()
        knownLibraryIds.clear()
        visitedElmKeysInStepSession.clear()
        lastPausedLine = -1
        lastPausedLibId = null
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

    /**
     * Submits an ad-hoc CQL expression for evaluation on the parked engine thread.
     * Called from the DAP `evaluate()` request thread.
     *
     * @param functionDef the compiled `__debugEval__` function (its operands are the query aliases)
     * @param argumentValues the live alias values, in the same order as [FunctionDef.getOperand]
     * @return a future that completes with the evaluated value (or an exception on failure)
     */
    fun submitAdHocEval(
        functionDef: FunctionDef,
        argumentValues: List<Value?>,
    ): CompletableFuture<Value?> {
        val future = CompletableFuture<Value?>()
        pendingEval.set(AdHocEvalRequest(functionDef, argumentValues, future))
        return future
    }

    /**
     * Runs a single ad-hoc evaluation on the engine thread. Swaps in a
     * [ContinueOnlyBreakpointHandler] to prevent re-entrant pausing, and defensively
     * verifies that the stack depth is restored afterward.
     */
    private fun runAdHocEval(request: AdHocEvalRequest) {
        val state = lastPausedState
        if (state == null) {
            request.resultFuture.completeExceptionally(IllegalStateException("no paused state"))
            return
        }
        require(request.argumentValues.size == request.functionDef.operand.size) {
            "ad-hoc eval argument count ${request.argumentValues.size} does not match function " +
                "operand count ${request.functionDef.operand.size}"
        }
        val savedHandler = state.breakpointHandler
        val depthBefore = state.stack.size
        state.breakpointHandler = ContinueOnlyBreakpointHandler
        try {
            val value =
                FunctionRefEvaluator.evaluateFunctionDef(
                    request.functionDef,
                    state,
                    EvaluationVisitor(),
                    request.argumentValues.toMutableList(),
                )
            request.resultFuture.complete(value)
        } catch (e: Exception) {
            request.resultFuture.completeExceptionally(e)
        } finally {
            state.breakpointHandler = savedHandler
            if (state.stack.size > depthBefore) {
                log.warn(
                    "runAdHocEval: stack depth {} > {} after ad-hoc eval; forcibly popping",
                    state.stack.size,
                    depthBefore,
                )
                while (state.stack.size > depthBefore) {
                    state.popActivationFrame()
                }
            }
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
                while (!resumeLatch.await(50, TimeUnit.MILLISECONDS)) {
                    pendingEval.getAndSet(null)?.let { request ->
                        log.debug("waitForResume: processing ad-hoc eval [thread={}]", Thread.currentThread().name)
                        runAdHocEval(request)
                    }
                }
                log.debug("waitForResume: latch released [+{}ms]", (System.nanoTime() - t0) / 1_000_000)
            }
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
