package com.gpiano.app.midi

import java.lang.reflect.Modifier
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiPracticeControllerVisibilityTest {
    @Test
    fun captureStateIsVisibleToMidiReceiverThread() {
        val stateField = MidiPracticeController::class.java.getDeclaredField("state")

        assertTrue(
            "MIDI callbacks read capture state off the main thread, so the field must remain volatile",
            Modifier.isVolatile(stateField.modifiers),
        )
    }
}
