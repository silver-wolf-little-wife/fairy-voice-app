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
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * 模拟 B 端（v2.0 流式）：ask / voice_ask → stream_begin → 多个 stream_delta → stream_end。
     * interruptAfterDeltas >= 0 时：发 N 个 delta 后直接断开（模拟流式中断），不发 stream_end。
     */
    private fun enqueueStreamingServer(
        deltas: List<String> = listOf("明天", "晴", "，23~31℃。"),
        fullText: String = "明天晴，23~31℃。",
        recognized: String? = "识别文本",
        interruptAfterDeltas: Int = -1,
    ) {
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                when {
                    text.contains("\"hello\"") -> webSocket.send(
                        """{"type":"hello_ack","ok":true,"session_id":"s1","server_version":"0.2.0"}"""
                    )
                    text.contains("\"ping\"") -> webSocket.send("""{"type":"pong"}""")
                    text.contains("\"voice_ask\"") || text.contains("\"ask\"") -> {
                        val id = Regex("\"id\":\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: ""
                        val rec = if (recognized != null) "\"$recognized\"" else "null"
                        webSocket.send("""{"type":"stream_begin","id":"$id","recognized":$rec}""")
                        var sent = 0
                        for (d in deltas) {
                            if (interruptAfterDeltas >= 0 && sent >= interruptAfterDeltas) break
                            webSocket.send("""{"type":"stream_delta","id":"$id","delta":"$d"}""")
                            sent++
                        }
                        if (interruptAfterDeltas >= 0) {
                            webSocket.close(1001, "server restart")
                        } else {
                            webSocket.send("""{"type":"stream_end","id":"$id","ok":true,"data":{"text":"$fullText"}}""")
                        }
                    }
                }
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
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

    private fun client(
        serverUrl: String = server.url("/ws").toString(),
        firstTokenTimeoutMs: Long = 30_000,
        idleTimeoutMs: Long = 15_000,
    ): FairyVoiceClient =
        FairyVoiceClient(
            serverUrl = serverUrl,
            token = "test-token",
            deviceId = "test-device",
            heartbeatIntervalMs = 100,
            askTimeoutMs = 3_000,
            handshakeTimeoutMs = 3_000,
            firstTokenTimeoutMs = firstTokenTimeoutMs,
            idleTimeoutMs = idleTimeoutMs,
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

    // ---------- v2.0 流式 ----------

    @Test
    fun `sendAsk streams and aggregates deltas`() {
        enqueueStreamingServer()
        val ready = CountDownLatch(1)
        val c = client()
        c.onReady = { ready.countDown() }
        c.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))

        val events = CopyOnWriteArrayList<String>()
        c.onStreamBegin = { id, rec -> events.add("begin($id,$rec)") }
        c.onStreamDelta = { id, d -> events.add("delta($id,$d)") }
        c.onStreamEnd = { id, ok, text -> events.add("end($id,$ok,$text)") }

        val resp = c.sendAsk("明天天气如何")
        assertTrue(resp.ok)
        assertEquals("明天晴，23~31℃。", resp.text)
        assertEquals(1, events.count { it.startsWith("begin(") })
        assertEquals(3, events.count { it.startsWith("delta(") })
        assertEquals(1, events.count { it.startsWith("end(") && it.contains(",true,") })
        c.stop()
    }

    @Test
    fun `sendVoiceAsk streams recognized through`() {
        enqueueStreamingServer()
        val ready = CountDownLatch(1)
        val c = client()
        c.onReady = { ready.countDown() }
        c.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))

        val resp = c.sendVoiceAsk("base64wavdata==")
        assertTrue(resp.ok)
        assertEquals("明天晴，23~31℃。", resp.text)
        assertEquals("识别文本", resp.recognized)
        c.stop()
    }

    @Test
    fun `stream interruption appends partial text with interrupted marker`() {
        // 服务器：ask → stream_begin + 1 个 delta，然后不发 stream_end（模拟 B 端流式卡死/断流）。
        // 测试端收到 delta 后主动 stop() 触发断流兜底：已收 delta 拼接 + 标记「已中断」。
        val deltaSent = CountDownLatch(1)
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                when {
                    text.contains("\"hello\"") -> webSocket.send(
                        """{"type":"hello_ack","ok":true,"session_id":"s1","server_version":"0.2.0"}"""
                    )
                    text.contains("\"ping\"") -> webSocket.send("""{"type":"pong"}""")
                    text.contains("\"ask\"") -> {
                        val id = Regex("\"id\":\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: ""
                        val sb = webSocket.send("""{"type":"stream_begin","id":"$id","recognized":null}""")
                        val sd = webSocket.send("""{"type":"stream_delta","id":"$id","delta":"部分"}""")
                        println("SERVER send begin=$sb delta=$sd")
                        deltaSent.countDown()
                    }
                }
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        server.enqueue(MockResponse().withWebSocketUpgrade(listener)) // 重连后应答，防 mockwebserver 空转

        val ready = CountDownLatch(1)
        val deltaArrived = CountDownLatch(1)
        val c = client()
        c.onReady = { if (ready.count > 0) ready.countDown() }
        c.onStreamDelta = { _, _ -> deltaArrived.countDown() } // 回调在聚合 append 之后触发
        c.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))

        val askDone = CountDownLatch(1)
        val respRef = AtomicReference<ResponseFrame?>()
        val askErr = AtomicReference<Throwable?>()
        Thread {
            try {
                respRef.set(c.sendAsk("明天天气如何"))
            } catch (e: Throwable) {
                askErr.set(e)
            } finally {
                askDone.countDown()
            }
        }.start()

        assertTrue("服务器应发出 delta", deltaSent.await(5, TimeUnit.SECONDS))
        assertTrue("客户端应收到并聚合 delta", deltaArrived.await(5, TimeUnit.SECONDS))
        c.stop() // 断流 → failAllPending → 拼接已收 + 已中断

        assertTrue("sendAsk 应返回而非永久阻塞", askDone.await(5, TimeUnit.SECONDS))
        assertTrue("不应抛异常: ${askErr.get()}", askErr.get() == null)
        val resp = respRef.get()
        assertTrue("应返回已收部分而非失败", resp != null && resp.ok)
        assertTrue("已收 delta 应保留: ${resp?.text}", resp?.text.orEmpty().contains("部分"))
        assertTrue("应标记已中断: ${resp?.text}", resp?.text.orEmpty().contains("（已中断）"))
    }

    @Test
    fun `first token timeout fails the request`() {
        // 服务器：握手成功但 ask 后不回复任何帧（模拟 LLM 迟迟不吐首 token）
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                when {
                    text.contains("\"hello\"") -> webSocket.send(
                        """{"type":"hello_ack","ok":true,"session_id":"s1","server_version":"0.2.0"}"""
                    )
                    text.contains("\"ping\"") -> webSocket.send("""{"type":"pong"}""")
                    // ask 不回复，触发首 token 超时
                }
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))

        val ready = CountDownLatch(1)
        val c = client(firstTokenTimeoutMs = 500)
        c.onReady = { ready.countDown() }
        c.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))

        try {
            c.sendAsk("hi")
            assertTrue("首 token 超时应抛 AskError", false)
        } catch (e: FairyVoiceException.AskError) {
            assertTrue("错误信息应含 timeout: ${e.message}", e.message.orEmpty().contains("timeout"))
            assertTrue("错误信息应含首 token: ${e.message}", e.message.orEmpty().contains("首 token"))
        }
        c.stop()
    }

    @Test
    fun `idle timeout between deltas completes with partial text`() {
        // 服务器：stream_begin + 1 delta 后停（不再发 delta/end），触发增量间空闲超时
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                when {
                    text.contains("\"hello\"") -> webSocket.send(
                        """{"type":"hello_ack","ok":true,"session_id":"s1","server_version":"0.2.0"}"""
                    )
                    text.contains("\"ping\"") -> webSocket.send("""{"type":"pong"}""")
                    text.contains("\"ask\"") -> {
                        val id = Regex("\"id\":\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: ""
                        webSocket.send("""{"type":"stream_begin","id":"$id","recognized":null}""")
                        webSocket.send("""{"type":"stream_delta","id":"$id","delta":"部分"}""")
                    }
                }
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))

        val ready = CountDownLatch(1)
        val c = client(idleTimeoutMs = 500)
        c.onReady = { ready.countDown() }
        c.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))

        val resp = c.sendAsk("hi")
        assertTrue("空闲超时应返回已收部分而非抛异常", resp.ok)
        assertTrue("已收 delta 应保留: ${resp.text}", resp.text.orEmpty().contains("部分"))
        assertTrue("应标记已中断: ${resp.text}", resp.text.orEmpty().contains("（已中断）"))
        c.stop()
    }
}
