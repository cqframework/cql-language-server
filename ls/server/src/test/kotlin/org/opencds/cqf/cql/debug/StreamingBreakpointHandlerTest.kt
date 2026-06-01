package org.opencds.cqf.cql.debug

import org.hl7.elm.r1.Element
import org.hl7.elm.r1.Literal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.engine.debug.BreakpointAction
import org.opencds.cqf.cql.engine.execution.Environment
import org.opencds.cqf.cql.engine.execution.State
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StreamingBreakpointHandlerTest {
    private fun makeElement(locator: String?): Element = Literal().also { it.locator = locator }

    private fun makeState(stackDepth: Int = 0): State {
        val state = State(Environment(null))
        repeat(stackDepth) {
            state.stack.addFirst(State.ActivationFrame(null, null, null, 0L))
        }
        return state
    }

    // -- parseLine ---------------------------------------------------------

    @Test
    fun `parseLine extracts line from standard locator`() {
        assertEquals(10, StreamingBreakpointHandler.parseLine("10:5-10:20"))
    }

    @Test
    fun `parseLine returns null for empty string`() {
        assertNull(StreamingBreakpointHandler.parseLine(""))
    }

    @Test
    fun `parseLine returns null for non-numeric locator`() {
        assertNull(StreamingBreakpointHandler.parseLine("abc:1-2:3"))
    }

    // -- STEP_IN ----------------------------------------------------------

    @Test
    fun `onBeforeExpression in STEP_IN pauses on first expression`() {
        val handler = StreamingBreakpointHandler()
        handler.stepIn()
        val elm = makeElement("5:1-5:10")
        val state = makeState(1)
        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(elm, state))
    }

    @Test
    fun `onBeforeExpression in STEP_IN continues on same line`() {
        val handler = StreamingBreakpointHandler()
        handler.stepIn()
        val elm1 = makeElement("5:1-5:10")
        val elm2 = makeElement("5:12-5:20")
        val state = makeState(1)

        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(elm1, state))
        assertEquals(BreakpointAction.CONTINUE, handler.onBeforeExpression(elm2, state))
    }

    @Test
    fun `onBeforeExpression in STEP_IN pauses on new line after resume`() {
        val handler = StreamingBreakpointHandler()
        handler.stepIn()
        val elm1 = makeElement("5:1-5:10")
        val elm2 = makeElement("6:1-6:10")
        val state = makeState(1)

        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(elm1, state))
        handler.stepIn()
        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(elm2, state))
    }

    @Test
    fun `onBeforeExpression in STEP_IN pauses on first expression after stepIn`() {
        val handler = StreamingBreakpointHandler()
        val state = makeState(1)

        // Switch to CONTINUE mode first
        handler.resume()
        val elm = makeElement("5:1-5:10")
        assertEquals(BreakpointAction.CONTINUE, handler.onBeforeExpression(elm, state))

        // Switch back to STEP_IN
        handler.stepIn()
        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(makeElement("6:1-6:10"), state))
    }

    // -- STEP_OVER ---------------------------------------------------------

    @Test
    fun `onBeforeExpression in STEP_OVER pauses at same-or-shallower depth and new line`() {
        val handler = StreamingBreakpointHandler()
        handler.stepIn()
        val state = makeState(2)
        val elm1 = makeElement("5:1-5:10")

        // First pause (STEP_IN)
        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(elm1, state))

        // Step over at depth 2
        handler.stepOver(2)
        val elm2 = makeElement("6:1-6:10")
        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(elm2, state))
    }

    @Test
    fun `onBeforeExpression in STEP_OVER continues at deeper depth`() {
        val handler = StreamingBreakpointHandler()
        handler.stepIn()
        val state = makeState(1)
        val elm1 = makeElement("5:1-5:10")

        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(elm1, state))

        // Step over at depth 1
        handler.stepOver(1)
        // Deeper depth (2 > 1) — should not pause even on new line
        val deeperState = makeState(2)
        assertEquals(BreakpointAction.CONTINUE, handler.onBeforeExpression(makeElement("6:1-6:10"), deeperState))
    }

    @Test
    fun `onBeforeExpression in STEP_OVER pauses when returning to shallower depth`() {
        val handler = StreamingBreakpointHandler()
        handler.stepIn()
        val state = makeState(2)
        val elm1 = makeElement("5:1-5:10")

        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(elm1, state))

        // Step over at depth 2 in a sub-expression
        handler.stepOver(2)
        val deeperState = makeState(3)
        assertEquals(BreakpointAction.CONTINUE, handler.onBeforeExpression(makeElement("6:1-6:10"), deeperState))

        // Returning to shallower depth (2) — should pause
        val sameDepthState = makeState(2)
        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(makeElement("7:1-7:10"), sameDepthState))
    }

    // -- STEP_OUT ----------------------------------------------------------

    @Test
    fun `onBeforeExpression in STEP_OUT pauses when depth decreases`() {
        val handler = StreamingBreakpointHandler()
        handler.stepIn()
        val state = makeState(3)
        val elm1 = makeElement("5:1-5:10")

        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(elm1, state))

        // Step out at depth 3
        handler.stepOut(3)
        // Same depth — should continue
        assertEquals(BreakpointAction.CONTINUE, handler.onBeforeExpression(makeElement("6:1-6:10"), state))

        // Shallower depth (2 < 3) — should pause
        val shallowerState = makeState(2)
        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(makeElement("7:1-7:10"), shallowerState))
    }

    @Test
    fun `onBeforeExpression in STEP_OUT continues at same depth`() {
        val handler = StreamingBreakpointHandler()
        val elm1 = makeElement("5:1-5:10")
        handler.stepOut(2)
        val state = makeState(2)
        assertEquals(BreakpointAction.CONTINUE, handler.onBeforeExpression(elm1, state))
    }

    // -- CONTINUE (breakpoint) ---------------------------------------------

    @Test
    fun `onBeforeExpression in CONTINUE pauses at breakpoint line`() {
        val handler = StreamingBreakpointHandler()
        handler.setBreakpoints(setOf(10))
        handler.resume()
        val elm = makeElement("10:1-10:15")
        val state = makeState(1)

        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(elm, state))
    }

    @Test
    fun `onBeforeExpression in CONTINUE continues at non-breakpoint line`() {
        val handler = StreamingBreakpointHandler()
        handler.setBreakpoints(setOf(10))
        handler.resume()
        val elm = makeElement("11:1-11:10")
        val state = makeState(1)

        assertEquals(BreakpointAction.CONTINUE, handler.onBeforeExpression(elm, state))
    }

    @Test
    fun `onBeforeExpression in CONTINUE continues at same line after pause`() {
        val handler = StreamingBreakpointHandler()
        handler.setBreakpoints(setOf(10))
        handler.resume()
        val state = makeState(1)

        val elm1 = makeElement("10:1-10:15")
        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(elm1, state))

        // Same breakpoint line after resume in CONTINUE mode should not re-pause
        handler.resume()
        val elm2 = makeElement("10:20-10:25")
        assertEquals(BreakpointAction.CONTINUE, handler.onBeforeExpression(elm2, state))
    }

    @Test
    fun `onBeforeExpression in CONTINUE pauses at same breakpoint after stepping to new line and back`() {
        val handler = StreamingBreakpointHandler()
        handler.setBreakpoints(setOf(10, 11))
        handler.resume()
        val state = makeState(1)

        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(makeElement("10:1-10:5"), state))

        handler.stepIn()
        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(makeElement("11:1-11:5"), state))

        // After resuming from line 11, hitting line 10 again should pause
        handler.stepIn()
        // Manually reset lastPausedLine to simulate the step
        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(makeElement("10:10-10:15"), state))
    }

    // -- latch pause/resume -------------------------------------------------

    @Test
    fun `pauseAndResume via stepIn unblocks evaluation`() {
        val handler = StreamingBreakpointHandler()
        handler.stepIn()
        val elm = makeElement("5:1-5:10")
        val state = makeState(1)

        // First pause
        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(elm, state))

        // Verify that waitForResume blocks (use a thread to release)
        val released = CountDownLatch(1)
        var actionAfterWait: BreakpointAction? = null

        Thread {
            handler.waitForResume()
            // After release, check the next expression
            actionAfterWait = handler.onBeforeExpression(makeElement("6:1-6:10"), makeState(1))
            released.countDown()
        }.apply {
            start()
        }

        // Give the thread time to block on waitForResume
        Thread.sleep(100)

        // Release from the test thread
        handler.stepIn()

        // Wait for the worker thread to complete
        assert(released.await(5, TimeUnit.SECONDS)) { "Thread should have been released" }
        assertEquals(BreakpointAction.PAUSE, actionAfterWait)
    }

    @Test
    fun `waitForResume returns immediately when latch already released`() {
        val handler = StreamingBreakpointHandler()
        handler.stepIn()
        val elm = makeElement("5:1-5:10")
        val state = makeState(1)

        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(elm, state))

        // Release before waitForResume is called
        handler.stepIn()

        // Should return immediately
        handler.waitForResume()
    }

    // -- onPauseCallback ---------------------------------------------------

    @Test
    fun `onPauseCallback is invoked on pause`() {
        val handler = StreamingBreakpointHandler()
        handler.stepIn()
        val elm = makeElement("5:1-5:10")
        val state = makeState(1)

        var invoked = false
        handler.onPauseCallback = { e, s ->
            invoked = true
            assertEquals(elm, e)
            assertEquals(state, s)
        }

        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(elm, state))
        handler.stepIn()
        handler.waitForResume()
        assert(invoked) { "onPauseCallback should have been invoked" }
    }

    // -- element with no locator -------------------------------------------

    @Test
    fun `onBeforeExpression returns CONTINUE for element without locator`() {
        val handler = StreamingBreakpointHandler()
        val elm = makeElement(null as String?) // literal at position unresolved
        elm.locator = null
        val state = makeState(1)
        assertEquals(BreakpointAction.CONTINUE, handler.onBeforeExpression(elm, state))
    }

    @Test
    fun `stepMode getter returns current mode`() {
        val handler = StreamingBreakpointHandler()
        assertEquals(StreamingBreakpointHandler.StepMode.CONTINUE, handler.getStepMode())

        handler.stepOver(1)
        assertEquals(StreamingBreakpointHandler.StepMode.STEP_OVER, handler.getStepMode())

        handler.stepOut(1)
        assertEquals(StreamingBreakpointHandler.StepMode.STEP_OUT, handler.getStepMode())

        handler.resume()
        assertEquals(StreamingBreakpointHandler.StepMode.CONTINUE, handler.getStepMode())

        handler.stepIn()
        assertEquals(StreamingBreakpointHandler.StepMode.STEP_IN, handler.getStepMode())
    }

    @Test
    fun `stepOut resets lastPausedLine to allow pausing on same line of caller frame`() {
        val handler = StreamingBreakpointHandler()
        handler.stepIn()

        val elm1 = makeElement("5:1-5:10")
        val state1 = makeState(2)

        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(elm1, state1))

        handler.stepOut(2)

        val elm2 = makeElement("5:12-5:20")
        val state2 = makeState(1)
        assertEquals(BreakpointAction.PAUSE, handler.onBeforeExpression(elm2, state2))
    }

    @Test
    fun `getBreakpointLines returns the set lines`() {
        val handler = StreamingBreakpointHandler()
        handler.setBreakpoints(setOf(5, 10, 15))
        assertEquals(setOf(5, 10, 15), handler.getBreakpointLines())
    }
}
