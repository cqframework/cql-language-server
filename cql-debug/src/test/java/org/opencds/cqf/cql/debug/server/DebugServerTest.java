package org.opencds.cqf.cql.debug.server;

import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.mockito.Mockito;
import org.testng.annotations.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.testng.Assert.*;

import java.util.HashMap;

public class DebugServerTest {

    @Test
    public void handshake() throws Exception {
        IDebugProtocolClient client = Mockito.mock(IDebugProtocolClient.class);
        DebugServer server = new DebugServer();
        server.connect(client);

        assertEquals(server.getState(), ServerState.STARTED);

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
        Capabilities capabilities = server.initialize(new InitializeRequestArguments()).get();
        assertNotNull(capabilities);

        // Server should send the "initialized" event once it's ready
        // TODO:
        Mockito.verify(client).initialized();
        assertEquals(server.getState(), ServerState.INITIALIZED);

        // SetBreakpointsResponse setBreakpointsResponse = server.setBreakpoints(new SetBreakpointsArguments()).get();
        // assertNotNull(setBreakpointsResponse);

        // SetFunctionBreakpointsResponse setFunctionBreakpointsResponse = server.setFunctionBreakpoints(new SetFunctionBreakpointsArguments()).get();
        // assertNotNull(setFunctionBreakpointsResponse);

        // TODO: in DAP 1.47+ this has a return type.
        // server.setExceptionBreakpoints(new SetExceptionBreakpointsArguments()).get();

        server.configurationDone(new ConfigurationDoneArguments()).get();
        assertEquals(server.getState(), ServerState.CONFIGURED);

        // Server should now be ready to launch...
        // The "launch" options are specific to the CQL implementation
        // Essentially, key-value pairs.
        server.launch(new HashMap<>()).get();

        // Breakpoints hit and so on...

        // terminated, and then exited
        Mockito.verify(client).terminated(any());
        Mockito.verify(client).exited(any());
        assertEquals(server.getState(), ServerState.STOPPED);

    }
    
}
