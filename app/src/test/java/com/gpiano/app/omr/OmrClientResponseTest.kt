package com.gpiano.app.omr

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OmrClientResponseTest {
    @Test
    fun oversizedSuccessHasStableErrorClassification() {
        val error = assertThrows(OmrServiceException::class.java) {
            AudiverisOmrClient().readJson(FakeConnection(200, "x".repeat(64 * 1024 + 1)))
        }
        assertEquals("response_too_large", error.code)
        assertEquals(false, error.retryable)
    }

    @Test
    fun oversizedErrorIsNotCollapsedIntoHttpStatus() {
        val error = assertThrows(OmrServiceException::class.java) {
            AudiverisOmrClient().ensureSuccess(FakeConnection(503, "x".repeat(64 * 1024 + 1)))
        }
        assertEquals("response_too_large", error.code)
    }

    @Test
    fun persistedDiagnosticsAreBounded() {
        assertEquals(4 * 1024, boundOmrDiagnostics("x".repeat(8 * 1024))?.length)
    }

    private class FakeConnection(private val status: Int, body: String) :
        HttpURLConnection(URL("http://127.0.0.1")) {
        private val bytes = body.toByteArray()
        override fun getResponseCode(): Int = status
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
        override fun getErrorStream(): InputStream = ByteArrayInputStream(bytes)
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
    }
}
