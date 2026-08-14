// SPDX-License-Identifier: AGPL-3.0-only
/**
 * OneBotClient 集成测试：本地 MockWebServer 模拟 AstrBot 的 aiocqhttp（universal 反向 WS）。
 */
package com.fairyvoice.app.protocol

import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OneBotClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    /** 模拟 aiocqhttp 服务端：收到事件后回一条 send_private_msg。 */
    private fun enqueueReplyServer(replyText: String = "AI 回复") {
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("\"post_type\"")) {
                    webSocket.send(
                        """{"action":"send_private_msg","params":{"user_id":10001,"message":[{"type":"text","data":{"text":"$replyText"}}]},"echo":{"seq":1}}"""
                    )
                }
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
    }

    private fun client(serverUrl: String = "ws://${server.hostName}:${server.port}/ws"): OneBotClient =
        OneBotClient(
            serverUrl = serverUrl,
            token = "test-token",
            selfId = "10086",
            userId = "10001",
            askTimeoutMs = 3_000,
        )

    private fun fastTimeoutClient(): OneBotClient =
        OneBotClient(
            serverUrl = "ws://${server.hostName}:${server.port}/ws",
            token = "test-token",
            selfId = "10086",
            userId = "10001",
            askTimeoutMs = 500,
        )

    @Test
    fun `连接成功后 onReady 触发`() {
        enqueueReplyServer()
        val ready = CountDownLatch(1)
        val c = client()
        c.onReady = { ready.countDown() }
        c.start()
        assertTrue("连接建立后应触发 onReady", ready.await(5, TimeUnit.SECONDS))
        assertTrue(c.isConnected)
        c.stop()
    }

    @Test
    fun `sendPrivateMessage 上报事件并收到回复`() {
        enqueueReplyServer(replyText = "明天晴")
        val ready = CountDownLatch(1)
        val c = client()
        c.onReady = { ready.countDown() }
        c.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))

        val reply = c.sendPrivateMessage("明天天气如何")
        assertEquals("明天晴", reply)
        c.stop()
    }

    @Test
    fun `onReply 回调触发`() {
        enqueueReplyServer(replyText = "你好呀")
        val ready = CountDownLatch(1)
        val replied = CountDownLatch(1)
        val c = client()
        c.onReady = { ready.countDown() }
        c.onReply = { replied.countDown() }
        c.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))

        c.sendPrivateMessage("在吗")
        assertTrue("收到回复应触发 onReply", replied.await(5, TimeUnit.SECONDS))
        c.stop()
    }

    @Test
    fun `未连接时 sendPrivateMessage 抛 NotConnected`() {
        val c = client()
        try {
            c.sendPrivateMessage("hi")
            assertTrue("未连接应抛 NotConnected", false)
        } catch (e: OneBotException.NotConnected) {
            // expected
        }
    }

    @Test
    fun `服务端不回时 sendPrivateMessage 超时抛 AskError`() {
        // 服务端连接后不回复
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                // 静默
            }
        }))
        val ready = CountDownLatch(1)
        val c = fastTimeoutClient()
        c.onReady = { ready.countDown() }
        c.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))

        try {
            c.sendPrivateMessage("hi")
            assertTrue("应超时抛 AskError", false)
        } catch (e: OneBotException.AskError) {
            assertEquals("timeout", e.code)
        }
        c.stop()
    }

    @Test
    fun `断线后自动重连`() {
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("\"post_type\"")) {
                    webSocket.send(
                        """{"action":"send_private_msg","params":{"user_id":10001,"message":[{"type":"text","data":{"text":"ok"}}]},"echo":{"seq":1}}"""
                    )
                }
            }
        }
        // 第一次：连接后立即断开；第二次：正常
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.close(1001, "server restart")
            }
        }))
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))

        val ready = CountDownLatch(1)
        val reconnect = CountDownLatch(1)
        val c = client()
        c.onReady = {
            if (ready.count > 0) ready.countDown() else reconnect.countDown()
        }
        c.start()
        assertTrue("首次连接应成功", ready.await(5, TimeUnit.SECONDS))
        assertTrue("断线后应自动重连", reconnect.await(10, TimeUnit.SECONDS))
        assertTrue(c.isConnected)
        c.stop()
    }

    @Test
    fun `get_stranger_info API 调用返回 stub 响应`() {
        val gotResponse = CountDownLatch(1)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                // 服务端主动调用 get_stranger_info
                webSocket.send(
                    """{"action":"get_stranger_info","params":{"user_id":10001,"no_cache":false},"echo":{"seq":2}}"""
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = JSONObject(text)
                if (o.optString("status") == "ok") {
                    assertEquals(2, o.optJSONObject("echo")?.getInt("seq"))
                    assertEquals(10001L, o.optJSONObject("data")?.getLong("user_id"))
                    gotResponse.countDown()
                }
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        val ready = CountDownLatch(1)
        val c = client()
        c.onReady = { ready.countDown() }
        c.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        assertTrue("get_stranger_info 应返回 stub 响应", gotResponse.await(5, TimeUnit.SECONDS))
        c.stop()
    }
}
