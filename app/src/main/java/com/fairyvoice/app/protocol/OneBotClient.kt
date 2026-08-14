// SPDX-License-Identifier: AGPL-3.0-only
/**
 * OneBot 11 反向 WS 客户端（C 端最小实现，对应 aiocqhttp 1.4.4 的 universal 单连接）。
 *
 * P0-1 实测规格：
 * - 连接 `ws://<host>:<port>/ws`，握手 Headers：`X-Client-Role: universal` + `X-Self-ID` + `Authorization: Bearer`（token 空则省略）。
 * - 一条 WS 连接同时承担：C→B 上报事件（私聊消息）、B→C 下发 API 调用（send_private_msg 等，C 处理后回 `{status,retcode,data,echo}`）。
 * - 指令语义：sendPrivateMessage 上报事件后阻塞等待 AstrBot 通过 send_private_msg 下发回复。
 *
 * 职责（纯 Kotlin + OkHttp，可 JVM 单测）：
 * - 主动外连 AstrBot（穿透 NAT）、断线指数退避重连（1s→60s 封顶）。
 * - 事件上报、API 请求分发/响应、pending 指令关联与超时。
 * 不包含任何 AI 逻辑（ASR 在 C 端另做，见 audio/OnnxAsr.kt）。
 */
package com.fairyvoice.app.protocol

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

sealed class OneBotException(message: String) : Exception(message) {
    class NotConnected(message: String = "未连接 AstrBot") : OneBotException(message)
    class Busy(message: String = "已有未完成指令，等待回复中") : OneBotException(message)
    class AskError(val code: String, message: String) : OneBotException("[$code] $message")
}

class OneBotClient(
    private val serverUrl: String,
    private val token: String,
    private val selfId: String,
    private val userId: String,
    private val askTimeoutMs: Long = 60_000,
    private val maxReconnectDelayMs: Long = 60_000,
) {
    // 可选回调（均在客户端工作线程触发，调用方自行切线程）
    var onReady: (() -> Unit)? = null
    /** 收到 send_private_msg 回复文本。 */
    var onReply: ((String) -> Unit)? = null
    var onDisconnect: (() -> Unit)? = null

    // 配置快照，供 holder 判断配置变化
    val serverUrlValue: String get() = serverUrl
    val tokenValue: String get() = token
    val selfIdValue: String get() = selfId
    val userIdValue: String get() = userId

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS) // OkHttp 内置 WS ping，检测死连接
        .build()
    private val loopExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "onebot-ws-loop").apply { isDaemon = true }
    }

    @Volatile private var ws: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var connected = false
    private val loopRunning = AtomicBoolean(false)
    private val loopThread = AtomicReference<Thread?>(null)

    /** 消息 ID 自增（事件 message_id / 回执 message_id 共用）。 */
    private val msgId = AtomicLong(1)

    // pending 指令（单用户串行，一次至多一个等待回复的指令）
    private val pendingLock = Any()
    private var pendingFuture: CompletableFuture<String>? = null

    val isConnected: Boolean get() = connected

    // ---------- 生命周期 ----------

    fun start() {
        stopped = false
        // 幂等：循环已在跑则忽略
        if (loopRunning.compareAndSet(false, true)) {
            loopExecutor.execute {
                loopThread.set(Thread.currentThread())
                try {
                    runLoop()
                } finally {
                    loopThread.set(null)
                    loopRunning.set(false)
                }
            }
        }
    }

    fun stop() {
        stopped = true
        ws?.close(1000, "bye")
        ws = null
        failPending(OneBotException.NotConnected("客户端已停止"))
        loopThread.get()?.interrupt()
    }

    /** 主循环：连接 + 指数退避重连。 */
    private fun runLoop() {
        var delayMs = 1_000L
        while (!stopped) {
            try {
                connectOnce()
                if (stopped) break
                delayMs = 1_000L
            } catch (_: Exception) {
                // 连接失败 / 断开，静默退避重试
            }
            if (stopped) break
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                break
            }
            delayMs = (delayMs * 2).coerceAtMost(maxReconnectDelayMs)
        }
    }

    /** 建立一次连接并服务到断开（阻塞）。 */
    private fun connectOnce() {
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val openOk = AtomicBoolean(false)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openOk.set(true)
                opened.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.countDown()
                closed.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleInbound(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.countDown()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }

        val request = Request.Builder().url(serverUrl).apply {
            header("X-Client-Role", "universal")
            header("X-Self-ID", selfId)
            if (token.isNotBlank()) header("Authorization", "Bearer $token")
        }.build()

        ws = client.newWebSocket(request, listener)
        opened.await()
        if (!openOk.get()) throw IOException("连接失败")

        // OneBot universal 无握手 ack，open 即就绪
        connected = true
        onReady?.invoke()
        try {
            closed.await()
        } finally {
            connected = false
            failPending(OneBotException.NotConnected("连接已断开"))
            onDisconnect?.invoke()
        }
    }

    // ---------- 上报 + 等回复 ----------

    /**
     * 上报私聊文本指令并阻塞等待 AstrBot 回复（回复经 send_private_msg API 调用带回）。
     * @throws OneBotException.NotConnected / Busy / AskError（超时）
     */
    @Throws(OneBotException::class)
    fun sendPrivateMessage(text: String): String {
        val sock = ws
        if (sock == null || !connected) throw OneBotException.NotConnected()
        val fut = CompletableFuture<String>()
        synchronized(pendingLock) {
            if (pendingFuture != null) throw OneBotException.Busy()
            pendingFuture = fut
        }
        val sent = sock.send(oneBotPrivateMessageEvent(selfId, userId, msgId.getAndIncrement(), text))
        if (!sent) {
            synchronized(pendingLock) { pendingFuture = null }
            throw OneBotException.NotConnected()
        }
        return try {
            fut.get(askTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            synchronized(pendingLock) { pendingFuture = null }
            throw OneBotException.AskError("timeout", "等待 AstrBot 回复超时")
        } catch (e: ExecutionException) {
            synchronized(pendingLock) { pendingFuture = null }
            throw when (val cause = e.cause) {
                is OneBotException -> cause
                else -> OneBotException.AskError("internal_error", cause?.message ?: "未知错误")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            synchronized(pendingLock) { pendingFuture = null }
            throw OneBotException.NotConnected("已中断")
        }
    }

    // ---------- 入站 API 分发 ----------

    private fun handleInbound(raw: String) {
        val req = parseOneBotApiRequest(raw) ?: return
        when (req.action) {
            "send_private_msg", "send_msg" -> handleSendMsg(req)
            "get_stranger_info" -> handleGetStrangerInfo(req)
            "get_msg" -> handleGetMsg(req)
            else -> sendApiFailed(req, "unsupported_action=${req.action}")
        }
    }

    private fun handleSendMsg(req: OneBotApiRequest) {
        val text = oneBotExtractText(req.params.opt("message"))
        sendApiOk(req, JSONObject().put("message_id", msgId.getAndIncrement()))
        resolvePending(text)
        onReply?.invoke(text)
    }

    private fun handleGetStrangerInfo(req: OneBotApiRequest) {
        val uid = req.params.optString("user_id", userId)
        sendApiOk(req, JSONObject().apply {
            put("user_id", uid.toLongOrNull() ?: 0L)
            put("nickname", "FairyVoice")
            put("age", 0)
            put("sex", "unknown")
        })
    }

    private fun handleGetMsg(req: OneBotApiRequest) {
        // 我方不主动发 reply 段，AstrBot 一般不会调用；给最小 stub 兜底
        sendApiOk(req, JSONObject().apply {
            put("message_id", 0L)
            put("user_id", userId.toLongOrNull() ?: 0L)
            put("message", JSONArrayOfEmptyText())
        })
    }

    private fun sendApiOk(req: OneBotApiRequest, data: JSONObject) {
        runCatching { ws?.send(oneBotApiResponse(ok = true, data = data, echo = req.echo)) }
    }

    private fun sendApiFailed(req: OneBotApiRequest, msg: String) {
        runCatching {
            ws?.send(oneBotApiResponse(ok = false, data = JSONObject().put("message", msg), echo = req.echo))
        }
    }

    private fun resolvePending(text: String) {
        synchronized(pendingLock) {
            pendingFuture?.complete(text)
            pendingFuture = null
        }
    }

    private fun failPending(e: OneBotException) {
        synchronized(pendingLock) {
            pendingFuture?.completeExceptionally(e)
            pendingFuture = null
        }
    }

    private fun JSONArrayOfEmptyText(): org.json.JSONArray =
        org.json.JSONArray().put(JSONObject().apply {
            put("type", "text")
            put("data", JSONObject().put("text", ""))
        })
}
