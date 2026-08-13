// SPDX-License-Identifier: AGPL-3.0-only
/**
 * FairyVoiceClient 集成测试：本地 MockWebServer 模拟 B 端（对应 Python 版 test_ws_client.py）。
 */
package com.fairyvoice.app.protocol

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FairyVoiceClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        // 客户端 stop() 是异步关闭，shutdown 时连接可能仍在收尾，忽略该异常
        runCatching { server.shutdown() }
    }

    /** 模拟 B 端：自动回复 hello_ack / pong / response。 */
    private fun enqueueFakeServer(ok: Boolean = true, replyText: String = "AI 回复") {
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                when {
                    text.contains("\"hello\"") -> {
                        val ack = if (ok) {
                            """{"type":"hello_ack","ok":true,"session_id":"s1","server_version":"0.1.0"}"""
                        } else {
                            """{"type":"hello_ack","ok":false,"error":"invalid_token"}"""
                        }
                        webSocket.send(ack)
                    }
                    text.contains("\"ping\"") -> webSocket.send("""{"type":"pong"}""")
                    text.contains("\"voice_ask\"") -> {
                        // 注意：voice_ask 包含子串 "ask"，必须放在 ask 分支之前判断
                        val id = Regex("\"id\":\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: ""
                        webSocket.send(
                            """{"type":"response","id":"$id","ok":true,"data":{"text":"$replyText","recognized":"识别文本"},"error":null}"""
                        )
                    }
                    text.contains("\"ask\"") -> {
                        val id = Regex("\"id\":\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: ""
                        webSocket.send(
                            """{"type":"response","id":"$id","ok":true,"data":{"text":"$replyText"},"error":null}"""
                        )
                    }
                }
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
    }

    private fun client(serverUrl: String = server.url("/ws").toString()): FairyVoiceClient =
        FairyVoiceClient(
            serverUrl = serverUrl,
            token = "test-token",
            deviceId = "test-device",
            heartbeatIntervalMs = 100,
            askTimeoutMs = 3_000,
            handshakeTimeoutMs = 3_000,
        )

    @Test
    fun `hello handshake succeeds and onReady fires`() {
        enqueueFakeServer()
        val ready = CountDownLatch(1)
        val c = client()
        c.onReady = { ready.countDown() }
        c.start()
        assertTrue("onReady 应在握手成功后触发", ready.await(5, TimeUnit.SECONDS))
        assertTrue(c.isConnected)
        c.stop()
    }

    @Test
    fun `invalid token triggers onAuthError`() {
        enqueueFakeServer(ok = false)
        val authErr = CountDownLatch(1)
        val c = client()
        c.onAuthError = { authErr.countDown() }
        c.start()
        assertTrue("token 错误应触发 onAuthError", authErr.await(5, TimeUnit.SECONDS))
        assertFalse(c.isConnected)
        c.stop()
    }

    @Test
    fun `sendAsk returns AI reply`() {
        enqueueFakeServer(replyText = "明天晴")
        val ready = CountDownLatch(1)
        val c = client()
        c.onReady = { ready.countDown() }
        c.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))

        val resp = c.sendAsk("明天天气如何")
        assertTrue(resp.ok)
        assertEquals("明天晴", resp.text)
        c.stop()
    }

    @Test
    fun `sendVoiceAsk returns AI reply with recognized`() {
        enqueueFakeServer(replyText = "明天晴")
        val ready = CountDownLatch(1)
        val c = client()
        c.onReady = { ready.countDown() }
        c.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))

        val resp = c.sendVoiceAsk("base64wavdata==")
        assertTrue(resp.ok)
        assertEquals("明天晴", resp.text)
        assertEquals("识别文本", resp.recognized)
        c.stop()
    }

    @Test
    fun `sendVoiceAsk before connected throws NotConnected`() {
        val c = client()
        try {
            c.sendVoiceAsk("base64wavdata==")
            assertTrue("未连接时应抛 NotConnected", false)
        } catch (e: FairyVoiceException.NotConnected) {
            // expected
        }
    }

    @Test
    fun `sendAsk before connected throws NotConnected`() {
        val c = client()
        try {
            c.sendAsk("hi")
            assertTrue("未连接时应抛 NotConnected", false)
        } catch (e: FairyVoiceException.NotConnected) {
            // expected
        }
    }

    @Test
    fun `reconnects after server closes connection`() {
        // 服务器：第一次握手成功后立即断开（模拟 B 端重启），第二次握手正常应答。
        // 注意：断开必须发生在 hello_ack 之后，否则客户端视为「握手期间断开」的
        // 瞬时故障（静默重试，不触发 onReady），reconnect 闩将永远等不到。
        val helloCount = AtomicInteger(0)
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                when {
                    text.contains("\"hello\"") -> {
                        if (helloCount.incrementAndGet() == 1) {
                            webSocket.send(
                                """{"type":"hello_ack","ok":true,"session_id":"s1","server_version":"0.1.0"}"""
                            )
                            webSocket.close(1001, "server restart")
                        } else {
                            webSocket.send(
                                """{"type":"hello_ack","ok":true,"session_id":"s2","server_version":"0.1.0"}"""
                            )
                        }
                    }
                    text.contains("\"ping\"") -> webSocket.send("""{"type":"pong"}""")
                }
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))

        val ready = CountDownLatch(1)
        val reconnect = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val c = client()
        c.onReady = {
            events.add("ready(count=${ready.count})")
            if (ready.count > 0) ready.countDown() else reconnect.countDown()
        }
        c.onDisconnect = { events.add("disconnect") }
        c.onAuthError = { events.add("authError=$it") }
        c.start()
        assertTrue("首次握手应成功", ready.await(5, TimeUnit.SECONDS))

        val reconnected = reconnect.await(10, TimeUnit.SECONDS)
        assertTrue("断线后应自动重连成功 事件=$events", reconnected)
        assertTrue(c.isConnected)
        c.stop()
    }
}
