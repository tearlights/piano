package com.gpiano.app.data

import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `recognition diagnostics are stripped from exported backup`() {
        val job = RecognitionJob(
            id = "job",
            sourceScoreId = "score",
            sourcePageIndex = 0,
            provider = "local",
            remoteJobId = "remote-secret",
            inputSha256 = "0".repeat(64),
            status = RecognitionJobStatus.Failed,
            stage = "download",
            attempt = 1,
            resultStructureId = null,
            errorCode = "network_error",
            errorMessage = "http://internal/path?token=secret",
            diagnosticsJson = "{\"response\":\"private\"}",
            createdAt = 1,
            updatedAt = 2,
        )

        val exported = job.sanitizedForBackup()

        assertNull(exported.remoteJobId)
        assertNull(exported.errorMessage)
        assertNull(exported.diagnosticsJson)
        assertEquals("network_error", exported.errorCode)
        assertEquals(RecognitionJobStatus.Failed, exported.status)
    }
}
