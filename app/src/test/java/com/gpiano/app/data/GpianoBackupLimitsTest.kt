package com.gpiano.app.data

import org.junit.Assert.assertThrows
import org.junit.Test

class GpianoBackupLimitsTest {
    @Test
    fun `export and import share the same json size boundary`() {
        requireBackupJsonSize("library.json", GpianoBackupManager.MAX_JSON_BYTES, GpianoBackupManager.MAX_JSON_BYTES)
        assertThrows(IllegalArgumentException::class.java) {
            requireBackupJsonSize(
                "library.json",
                GpianoBackupManager.MAX_JSON_BYTES + 1,
                GpianoBackupManager.MAX_JSON_BYTES,
            )
        }
    }
}
