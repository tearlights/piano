package com.gpiano.app.ai

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PracticeAiClientResponseTest {
    @Test
    fun oversizedSuccessHasStableErrorClassification() {
        val connection = FakeConnection(200, "x".repeat(512 * 1024 + 1))

        val error = assertThrows(PracticeAiServiceException::class.java) {
            PracticeAiClient().readJson(connection)
        }

        assertEquals("response_too_large", error.code)
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
