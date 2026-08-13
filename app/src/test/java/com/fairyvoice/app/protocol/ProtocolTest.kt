// SPDX-License-Identifier: AGPL-3.0-only
package com.fairyvoice.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun `helloFrame contains token device_id and version`() {
        val raw = helloFrame("t0k3n", "dev-1")
        assertTrue(raw.contains("\"type\":\"hello\""))
        assertTrue(raw.contains("\"token\":\"t0k3n\""))
        assertTrue(raw.contains("\"device_id\":\"dev-1\""))
        assertTrue(raw.contains("\"client_version\":\"0.1.0\""))
    }

    @Test
    fun `askFrame carries id text lang`() {
        val raw = askFrame("abc", "帮我查天气", "zh-CN")
        assertTrue(raw.contains("\"id\":\"abc\""))
        assertTrue(raw.contains("\"text\":\"帮我查天气\""))
        assertTrue(raw.contains("\"lang\":\"zh-CN\""))
    }

    @Test
    fun `HelloAck parse ok`() {
        val ack = HelloAck.parse("""{"type":"hello_ack","ok":true,"session_id":"s1","server_version":"0.1.0"}""")
        assertTrue(ack.ok)
        assertEquals("s1", ack.sessionId)
    }

    @Test
    fun `HelloAck parse rejected`() {
        val ack = HelloAck.parse("""{"type":"hello_ack","ok":false,"error":"invalid_token"}""")
        assertFalse(ack.ok)
        assertEquals("invalid_token", ack.error)
    }

    @Test
    fun `HelloAck parse garbage is safe`() {
        val ack = HelloAck.parse("not-json")
        assertFalse(ack.ok)
    }

    @Test
    fun `ResponseFrame parse success carries text`() {
        val raw = """{"type":"response","id":"abc","ok":true,"data":{"text":"明天晴，23~31℃。"},"error":null}"""
        val frame = ResponseFrame.parse(raw)
        assertEquals("abc", frame.id)
        assertTrue(frame.ok)
        assertEquals("明天晴，23~31℃。", frame.text)
    }

    @Test
    fun `ResponseFrame parse error carries code`() {
        val raw = """{"type":"response","id":"abc","ok":false,"data":null,"error":{"code":"llm_error","message":"boom"}}"""
        val frame = ResponseFrame.parse(raw)
        assertFalse(frame.ok)
        assertEquals("llm_error", frame.errorCode)
        assertEquals("boom", frame.errorMessage)
    }
}
