package org.opencds.cqf.cql.debug

import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito

class DebugServerTest {
    @Test
    fun handshake() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = CqlDebugServer()
        server.connect(client)

        assertEquals(ServerState.STARTED, server.getState())

        // https://microsoft.github.io/debug-adapter-protocol/overview

        // Sequence for initialization
        // initialize
        // (initialized)
        // setBreakpoints
        // setFunctionBreakpoints
        // setExceptionBreakpoints

        // do debugging loop...

        // server terminated
        // server exited
        val capabilities: Capabilities = server.initialize(InitializeRequestArguments()).get()
        assertNotNull(capabilities)

        // Server should send the "initialized" event once it's ready
        Mockito.verify(client).initialized()
        assertEquals(ServerState.INITIALIZED, server.getState())

        // SetBreakpointsResponse setBreakpointsResponse = server.setBreakpoints(new
        // SetBreakpointsArguments()).get();
        // assertNotNull(setBreakpointsResponse);

        // SetFunctionBreakpointsResponse setFunctionBreakpointsResponse =
        // server.setFunctionBreakpoints(new SetFunctionBreakpointsArguments()).get();
        // assertNotNull(setFunctionBreakpointsResponse);

        // TODO: in DAP 1.47+ this has a return type.
        // server.setExceptionBreakpoints(new SetExceptionBreakpointsArguments()).get();

        server.configurationDone(ConfigurationDoneArguments()).get()
        assertEquals(ServerState.CONFIGURED, server.getState())

        // Server should now be ready to launch...
        // The "launch" options are specific to the CQL implementation
        // Essentially, key-value pairs.
        server.launch(HashMap()).get()

        // Breakpoints hit and so on...

        // terminated, and then exited
        Mockito.verify(client).terminated(any())
        Mockito.verify(client).exited(any())
        assertEquals(ServerState.STOPPED, server.getState())
    }

    // -----------------------------------------------------------------------
    // checkState guards — wrong-order calls must throw IllegalStateException
    // -----------------------------------------------------------------------

    @Test
    fun initialize_whenAlreadyInitialized_throwsIllegalState() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = CqlDebugServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        assertEquals(ServerState.INITIALIZED, server.getState())

        // Server is now INITIALIZED; initialize() requires STARTED → must throw
        assertThrows(IllegalStateException::class.java) {
            server.initialize(InitializeRequestArguments())
        }
    }

    @Test
    fun configurationDone_whenNotInitialized_throwsIllegalState() {
        val server = CqlDebugServer()
        // State is STARTED, not INITIALIZED → must throw
        assertThrows(IllegalStateException::class.java) {
            server.configurationDone(ConfigurationDoneArguments())
        }
    }

    @Test
    fun launch_whenNotConfigured_throwsIllegalState() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = CqlDebugServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        // State is INITIALIZED, not CONFIGURED → must throw
        assertThrows(IllegalStateException::class.java) {
            server.launch(HashMap())
        }
    }

    // -----------------------------------------------------------------------
    // exited() future — completes after full lifecycle
    // -----------------------------------------------------------------------

    @Test
    fun exited_completesAfterFullLifecycle() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = CqlDebugServer()
        server.connect(client)
        server.initialize(InitializeRequestArguments()).get()
        server.configurationDone(ConfigurationDoneArguments()).get()
        server.launch(HashMap()).get()

        assertTrue(server.exited().isDone, "exited() future should be completed after the full launch lifecycle")
    }

    // -----------------------------------------------------------------------
    // disconnect — accepted from any state
    // -----------------------------------------------------------------------

    @Test
    fun disconnect_fromStartedState_completesWithoutThrowing() {
        val client = Mockito.mock(IDebugProtocolClient::class.java)
        val server = CqlDebugServer()
        server.connect(client)
        // disconnect() does not check state; should complete cleanly from STARTED
        server.disconnect(DisconnectArguments()).get()
        assertEquals(ServerState.STOPPED, server.getState())
    }
}
