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
        assertTrue(raw.contains("\"client_version\":\"0.2.0\""))
    }

    @Test
    fun `askFrame carries id text lang`() {
        val raw = askFrame("abc", "帮我查天气", "zh-CN")
        assertTrue(raw.contains("\"id\":\"abc\""))
        assertTrue(raw.contains("\"text\":\"帮我查天气\""))
        assertTrue(raw.contains("\"lang\":\"zh-CN\""))
    }

    @Test
    fun `voiceAskFrame carries id audio lang`() {
        val raw = voiceAskFrame("abc", "AAAA", "zh-CN")
        assertTrue(raw.contains("\"type\":\"voice_ask\""))
        assertTrue(raw.contains("\"id\":\"abc\""))
        assertTrue(raw.contains("\"audio\":\"AAAA\""))
        assertTrue(raw.contains("\"lang\":\"zh-CN\""))
    }

    @Test
    fun `ResponseFrame parse carries recognized`() {
        val raw = """{"type":"response","id":"abc","ok":true,"data":{"text":"明天晴","recognized":"明天天气怎么样"},"error":null}"""
        val frame = ResponseFrame.parse(raw)
        assertEquals("abc", frame.id)
        assertTrue(frame.ok)
        assertEquals("明天晴", frame.text)
        assertEquals("明天天气怎么样", frame.recognized)
    }

    @Test
    fun `ResponseFrame without recognized keeps null`() {
        val raw = """{"type":"response","id":"abc","ok":true,"data":{"text":"明天晴"},"error":null}"""
        val frame = ResponseFrame.parse(raw)
        assertEquals("明天晴", frame.text)
        assertEquals(null, frame.recognized)
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

    // ---------- v2.0 流式帧 ----------

    @Test
    fun `StreamBeginFrame parse carries recognized`() {
        val raw = """{"type":"stream_begin","id":"abc","recognized":"今天天气怎么样"}"""
        val frame = StreamBeginFrame.parse(raw)
        assertEquals("abc", frame.id)
        assertEquals("今天天气怎么样", frame.recognized)
    }

    @Test
    fun `StreamBeginFrame without recognized keeps null`() {
        val frame = StreamBeginFrame.parse("""{"type":"stream_begin","id":"abc"}""")
        assertEquals("abc", frame.id)
        assertEquals(null, frame.recognized)
    }

    @Test
    fun `StreamDeltaFrame parse carries delta`() {
        val frame = StreamDeltaFrame.parse("""{"type":"stream_delta","id":"abc","delta":"明天"}""")
        assertEquals("abc", frame.id)
        assertEquals("明天", frame.delta)
    }

    @Test
    fun `StreamEndFrame parse ok carries full text`() {
        val raw = """{"type":"stream_end","id":"abc","ok":true,"data":{"text":"明天晴，23~31℃。"}}"""
        val frame = StreamEndFrame.parse(raw)
        assertEquals("abc", frame.id)
        assertTrue(frame.ok)
        assertEquals("明天晴，23~31℃。", frame.text)
        assertEquals(null, frame.errorCode)
    }

    @Test
    fun `StreamEndFrame parse error carries code`() {
        val raw = """{"type":"stream_end","id":"abc","ok":false,"data":null,"error":{"code":"llm_error","message":"boom"}}"""
        val frame = StreamEndFrame.parse(raw)
        assertFalse(frame.ok)
        assertEquals("llm_error", frame.errorCode)
        assertEquals("boom", frame.errorMessage)
        assertEquals(null, frame.text)
    }

    @Test
    fun `StreamEndFrame parse garbage is safe`() {
        val frame = StreamEndFrame.parse("not-json")
        assertFalse(frame.ok)
    }
}
