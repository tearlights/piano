package com.gpiano.app.midi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceMidiSessionStateTest {
    @Test
    fun recordingMustBeResolvedBeforeLeavingWorkspace() {
        val state = WorkspaceMidiSessionState(
            capture = MidiCaptureUiState(connection = MidiConnectionState.Recording),
        )

        assertTrue(state.hasUnfinishedRecording)
    }

    @Test
    fun interruptedCaptureStillRequiresAnExplicitDecision() {
        val state = WorkspaceMidiSessionState(
            capture = MidiCaptureUiState(
                connection = MidiConnectionState.Disconnected,
                captureInterrupted = true,
            ),
        )

        assertTrue(state.hasUnfinishedRecording)
    }

    @Test
    fun connectedIdleDeviceDoesNotBlockWorkspaceExit() {
        val state = WorkspaceMidiSessionState(
            capture = MidiCaptureUiState(connection = MidiConnectionState.Connected),
        )

        assertFalse(state.hasUnfinishedRecording)
    }
}
