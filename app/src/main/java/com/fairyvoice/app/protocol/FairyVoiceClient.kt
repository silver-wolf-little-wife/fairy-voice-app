// SPDX-License-Identifier: AGPL-3.0-only
/**
 * B 端 WebSocket 客户端（fairy-voice 协议 v1.0.0）。
 *
 * 职责（对应 Python 版 ws_client.py）：
 * - 主动外连 B 端（穿透 NAT），hello 握手 → 心跳 → ask 请求/响应。
 * - 断线指数退避重连（1s → 60s 封顶）。
 * - 纯终端：只发指令文本、收 AI 回复，无任何 AI 逻辑。
 *
 * 纯 Kotlin + OkHttp，不依赖 Android API，可在 JVM 单测（MockWebServer）。
 */
package com.fairyvoice.app.protocol

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

sealed class FairyVoiceException(message: String) : Exception(message) {
    class NotConnected(message: String = "未连接 B 端") : FairyVoiceException(message)
    class AuthError(code: String, message: String) : FairyVoiceException("[$code] $message")
    class AskError(code: String, message: String) : FairyVoiceException("[$code] $message")
}

class FairyVoiceClient(
    private val serverUrl: String,
    private val token: String,
    private val deviceId: String,
    private val heartbeatIntervalMs: Long = 15_000,
    private val askTimeoutMs: Long = 60_000,
    private val maxReconnectDelayMs: Long = 60_000,
    private val handshakeTimeoutMs: Long = 10_000,
) {
    // 可选回调（均在客户端工作线程触发，调用方自行切线程）
    var onReady: (() -> Unit)? = null
    var onReply: ((String) -> Unit)? = null
    var onDisconnect: (() -> Unit)? = null
    var onAuthError: ((String) -> Unit)? = null

    /** 当前实例的配置快照，供 FairyClientHolder 判断配置是否变化（变化则重建）。 */
    val serverUrlValue: String get() = serverUrl
    val tokenValue: String get() = token
    val deviceIdValue: String get() = deviceId

    private val client = OkHttpClient.Builder().build()
    private val loopExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "fairy-ws-loop").apply { isDaemon = true }
    }
    private val heartbeatExecutor = Executors.newScheduledThreadPool(1) { r ->
        Thread(r, "fairy-hb").apply { isDaemon = true }
    }

    @Volatile private var ws: WebSocket? = null
    private val pending = ConcurrentHashMap<String, CompletableFuture<ResponseFrame>>()
    @Volatile private var stopped = false
    @Volatile private var connected = false
    private var heartbeatTask: ScheduledFuture<*>? = null

    /** 重连循环是否已在运行（防止重复 start() 提交多个循环互相抢 ws）。 */
    private val loopRunning = AtomicBoolean(false)
    /** 当前运行 runLoop 的线程，stop() 时中断其 sleep。 */
    private val loopThread = AtomicReference<Thread?>(null)

    // ---------- 生命周期 ----------

    fun start() {
        stopped = false
        // 幂等：循环已在跑则忽略，避免多循环竞争同一 ws 引用导致握手永远失败
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
        cancelHeartbeat()
        failAllPending(FairyVoiceException.NotConnected("客户端已停止"))
        // 中断 runLoop 的 sleep，让循环尽快退出（下次 start() 才能重新提交）
        loopThread.get()?.interrupt()
    }

    val isConnected: Boolean get() = connected

    // ---------- 主循环：连接 + 指数退避重连 ----------

    private fun runLoop() {
        var delayMs = 1_000L
        while (!stopped) {
            try {
                connectOnce()
                if (stopped) break
                delayMs = 1_000L // 连接成功过，重置退避
            } catch (e: FairyVoiceException.AuthError) {
                onAuthError?.invoke(e.message ?: "握手失败")
            } catch (e: Exception) {
                // 连接失败 / 握手期间断开 / 网络异常，走退避静默重试
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
        val helloAckRef = AtomicReference<HelloAck?>(null)
        val helloLatch = CountDownLatch(1)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openOk.set(true)
                opened.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.countDown()
                helloLatch.countDown()
                closed.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val ack = helloAckRef.get()
                if (ack == null) {
                    // 握手阶段：第一条业务帧必须是 hello_ack（pong 也可能先到）
                    if (text.contains("\"hello_ack\"")) {
                        helloAckRef.set(HelloAck.parse(text))
                        helloLatch.countDown()
                    }
                    return
                }
                // 已握手：只处理 response
                if (text.contains("\"response\"")) {
                    val frame = ResponseFrame.parse(text)
                    if (frame.id.isNotEmpty()) resolvePending(frame)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                helloLatch.countDown()
                closed.countDown()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }

        val request = Request.Builder().url(serverUrl).build()
        ws = client.newWebSocket(request, listener)
        opened.await()
        if (!openOk.get()) throw IOException("连接失败")

        // 握手。握手期间连接被断开/超时属于瞬时故障（如 B 端重启），抛 IOException
        // 走静默退避重试；只有 ack.ok=false（token 被拒）才算真正的认证错误。
        ws?.send(helloFrame(token, deviceId))
        if (!helloLatch.await(handshakeTimeoutMs, TimeUnit.MILLISECONDS)) {
            throw IOException("握手超时")
        }
        val ack = helloAckRef.get() ?: throw IOException("握手期间连接断开")
        if (!ack.ok) throw FairyVoiceException.AuthError(ack.error ?: "unknown", ack.error ?: "握手被拒绝")

        // 握手成功，进入服务态
        connected = true
        onReady?.invoke()
        startHeartbeat()
        try {
            closed.await() // 阻塞直到断开
        } finally {
            connected = false
            cancelHeartbeat()
            failAllPending(FairyVoiceException.NotConnected("连接已断开"))
            onDisconnect?.invoke()
        }
    }

    // ---------- 心跳 ----------

    private fun startHeartbeat() {
        cancelHeartbeat()
        heartbeatTask = heartbeatExecutor.scheduleWithFixedDelay({
            runCatching { ws?.send(pingFrame()) }
        }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS)
    }

    private fun cancelHeartbeat() {
        heartbeatTask?.cancel(false)
        heartbeatTask = null
    }

    // ---------- ask / voice_ask ----------

    /** 发送语音指令文本，阻塞等待 B 端响应（最多 askTimeoutMs）。 */
    @Throws(FairyVoiceException::class)
    fun sendAsk(text: String, lang: String = "zh-CN"): ResponseFrame {
        val sock = ws
        if (sock == null || !connected) throw FairyVoiceException.NotConnected()
        val id = UUID.randomUUID().toString()
        val fut = CompletableFuture<ResponseFrame>()
        pending[id] = fut
        if (!sock.send(askFrame(id, text, lang))) {
            pending.remove(id)
            throw FairyVoiceException.NotConnected()
        }
        return try {
            fut.get(askTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pending.remove(id)
            throw FairyVoiceException.AskError("timeout", "等待 B 端响应超时")
        } catch (e: ExecutionException) {
            pending.remove(id)
            val cause = e.cause
            throw when (cause) {
                is FairyVoiceException -> cause
                else -> FairyVoiceException.AskError("internal_error", cause?.message ?: "未知错误")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            pending.remove(id)
            throw FairyVoiceException.NotConnected("已中断")
        }
    }

    /** 发送语音指令（M4-2：WAV base64 → B 端 ASR 识别 → AI 回复），阻塞等待。 */
    @Throws(FairyVoiceException::class)
    fun sendVoiceAsk(audioBase64: String, lang: String = "zh-CN"): ResponseFrame {
        val sock = ws
        if (sock == null || !connected) throw FairyVoiceException.NotConnected()
        val id = UUID.randomUUID().toString()
        val fut = CompletableFuture<ResponseFrame>()
        pending[id] = fut
        if (!sock.send(voiceAskFrame(id, audioBase64, lang))) {
            pending.remove(id)
            throw FairyVoiceException.NotConnected()
        }
        return try {
            fut.get(askTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pending.remove(id)
            throw FairyVoiceException.AskError("timeout", "等待 B 端响应超时")
        } catch (e: ExecutionException) {
            pending.remove(id)
            val cause = e.cause
            throw when (cause) {
                is FairyVoiceException -> cause
                else -> FairyVoiceException.AskError("internal_error", cause?.message ?: "未知错误")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            pending.remove(id)
            throw FairyVoiceException.NotConnected("已中断")
        }
    }

    // ---------- 内部 ----------

    private fun resolvePending(frame: ResponseFrame) {
        val fut = pending.remove(frame.id) ?: return
        if (!fut.isDone) {
            fut.complete(frame)
            onReply?.invoke(frame.text ?: "")
        }
    }

    private fun failAllPending(reason: FairyVoiceException) {
        pending.values.forEach { fut ->
            if (!fut.isDone) fut.completeExceptionally(reason)
        }
        pending.clear()
    }
}
