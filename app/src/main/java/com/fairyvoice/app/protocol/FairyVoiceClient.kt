// SPDX-License-Identifier: AGPL-3.0-only
/**
 * B 端 WebSocket 客户端（fairy-voice 协议 v2.0 流式）。
 *
 * 职责（对应 Python 版 ws_client.py）：
 * - 主动外连 B 端（穿透 NAT），hello 握手 → 心跳 → ask 请求/响应。
 * - 断线指数退避重连（1s → 60s 封顶）。
 * - v2.0 流式：ask / voice_ask 默认走流式（stream_begin / stream_delta / stream_end），
 *   通过 [onStreamBegin]/[onStreamDelta]/[onStreamEnd] 实时回调；阻塞返回在 stream_end 时完成，
 *   data.text 为完整回复。单帧 response 仅作 B 端非流式兜底保留。
 * - 断流兜底：已收到 stream_begin 但未收 stream_end 时连接断开 → 以已收 delta 拼接 + 标记「已中断」。
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
    /**
     * 首 token 超时（对齐 CherryStudio IdleTimeoutController 的流式健壮性）。
     * 发送 ask 后，LLM 迟迟不吐第一个 token（未收 stream_begin/response）超过该时长 → 该请求失败。
     * 60s：给 B 端工具调用/LLM 首 token 足够时间（网络搜索/远程设备操作可能 >30s）。
     */
    private val firstTokenTimeoutMs: Long = 60_000,
    /**
     * 流式增量间空闲超时。已开始流式（收到 stream_begin）后，相邻 delta 间隔超过该时长
     * → 判定 B 端卡死/断流，以已收 delta 拼接 + 标记「已中断」收尾（已收内容完整可读）。
     * 60s：B 端工具调用/LLM 长思考间隔可能远超 15s，避免误判断流。
     */
    private val idleTimeoutMs: Long = 60_000,
) {
    // 可选回调（均在客户端工作线程触发，调用方自行切线程）
    var onReady: (() -> Unit)? = null
    var onReply: ((String) -> Unit)? = null
    var onDisconnect: (() -> Unit)? = null
    var onAuthError: ((String) -> Unit)? = null

    // v2.0 流式回调（按 id 关联）
    /** 流开始：该 id 进入流式状态；voice_ask 时 recognized 为识别文本，ask 为 null。 */
    var onStreamBegin: ((id: String, recognized: String?) -> Unit)? = null
    /** 流增量：delta 为增量文本片段，直接追加展示。 */
    var onStreamDelta: ((id: String, delta: String) -> Unit)? = null
    /** 流结束：ok=true 且 text 为完整回复；ok=false 表示流中途失败（text 为已收部分）。 */
    var onStreamEnd: ((id: String, ok: Boolean, text: String?) -> Unit)? = null

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

    /** v2.0 流式聚合状态：已收到 stream_begin 但尚未收 stream_end 的 id → 增量文本拼接。 */
    private val streaming = ConcurrentHashMap<String, StringBuilder>()
    /**
     * v2.0 流式聚合状态：stream_begin 携带的 recognized（voice_ask 时非空）。
     * 注意：ConcurrentHashMap 不允许 null value，recognized 为 null 时不存入。
     */
    private val streamingRecognized = ConcurrentHashMap<String, String>()

    /** 首 token 超时计时器（按 id）。 */
    private val firstTokenTimers = ConcurrentHashMap<String, ScheduledFuture<*>>()
    /** 流式增量间空闲超时计时器（按 id）。 */
    private val idleTimers = ConcurrentHashMap<String, ScheduledFuture<*>>()

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
                // 已握手：处理流式帧（v2.0）与非流式 response 兜底。
                // 单帧解析异常不应中断 WS 读线程（否则后续 delta 丢失），吞掉并继续。
                runCatching {
                    when {
                        text.contains("\"stream_begin\"") -> {
                            val frame = StreamBeginFrame.parse(text)
                            if (frame.id.isNotEmpty()) handleStreamBegin(frame)
                        }
                        text.contains("\"stream_delta\"") -> {
                            val frame = StreamDeltaFrame.parse(text)
                            if (frame.id.isNotEmpty()) handleStreamDelta(frame)
                        }
                        text.contains("\"stream_end\"") -> {
                            val frame = StreamEndFrame.parse(text)
                            if (frame.id.isNotEmpty()) handleStreamEnd(frame)
                        }
                        text.contains("\"response\"") -> {
                            val frame = ResponseFrame.parse(text)
                            if (frame.id.isNotEmpty()) resolvePending(frame)
                        }
                    }
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
        startFirstTokenTimer(id, fut)
        if (!sock.send(askFrame(id, text, lang))) {
            cleanupRequest(id)
            throw FairyVoiceException.NotConnected()
        }
        return try {
            fut.get(askTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            cleanupRequest(id)
            throw FairyVoiceException.AskError("timeout", "等待 B 端响应超时")
        } catch (e: ExecutionException) {
            cleanupRequest(id)
            val cause = e.cause
            throw when (cause) {
                is FairyVoiceException -> cause
                else -> FairyVoiceException.AskError("internal_error", cause?.message ?: "未知错误")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            cleanupRequest(id)
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
        startFirstTokenTimer(id, fut)
        if (!sock.send(voiceAskFrame(id, audioBase64, lang))) {
            cleanupRequest(id)
            throw FairyVoiceException.NotConnected()
        }
        return try {
            fut.get(askTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            cleanupRequest(id)
            throw FairyVoiceException.AskError("timeout", "等待 B 端响应超时")
        } catch (e: ExecutionException) {
            cleanupRequest(id)
            val cause = e.cause
            throw when (cause) {
                is FairyVoiceException -> cause
                else -> FairyVoiceException.AskError("internal_error", cause?.message ?: "未知错误")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            cleanupRequest(id)
            throw FairyVoiceException.NotConnected("已中断")
        }
    }

    // ---------- 流式超时看门狗（对齐 CherryStudio IdleTimeoutController） ----------

    /** 启动首 token 计时器：发送 ask 后 LLM 迟迟不吐首 token（未收 stream_begin/response）→ 该请求失败。 */
    private fun startFirstTokenTimer(id: String, fut: CompletableFuture<ResponseFrame>) {
        cancelFirstTokenTimer(id)
        val task = heartbeatExecutor.schedule({
            firstTokenTimers.remove(id)
            onFirstTokenTimeout(id)
        }, firstTokenTimeoutMs, TimeUnit.MILLISECONDS)
        firstTokenTimers[id] = task
    }

    /** 首 token 超时：请求未开始流式即超时，按失败处理（区别于断流兜底）。 */
    private fun onFirstTokenTimeout(id: String) {
        val fut = pending.remove(id) ?: return // 已被其他路径处理（收到 begin/end/response）
        streaming.remove(id)
        streamingRecognized.remove(id)
        if (!fut.isDone) {
            fut.completeExceptionally(FairyVoiceException.AskError("timeout", "等待 B 端首 token 超时"))
        }
    }

    private fun cancelFirstTokenTimer(id: String) {
        firstTokenTimers.remove(id)?.cancel(false)
    }

    /** 启动/重置空闲计时器：流式进行中相邻 delta 间隔超时 → 以已收 delta 拼接收尾（已中断）。 */
    private fun startIdleTimer(id: String) {
        cancelIdleTimer(id)
        val task = heartbeatExecutor.schedule({
            idleTimers.remove(id)
            onIdleTimeout(id)
        }, idleTimeoutMs, TimeUnit.MILLISECONDS)
        idleTimers[id] = task
    }

    /** 每次增量到达重置空闲计时。 */
    private fun resetIdleTimer(id: String) = startIdleTimer(id)

    /** 空闲超时：流中途无新 delta，判定 B 端卡死/断流，已收内容完整保留。 */
    private fun onIdleTimeout(id: String) {
        val fut = pending.remove(id) ?: return // 已被 stream_end/断流等处理
        val sb = streaming.remove(id)
        streamingRecognized.remove(id)
        val partial = sb?.toString() ?: ""
        onStreamEnd?.invoke(id, false, partial)
        if (!fut.isDone) {
            fut.complete(ResponseFrame(id, true, partial + "\n\n（已中断）", null, null, null))
        }
    }

    private fun cancelIdleTimer(id: String) {
        idleTimers.remove(id)?.cancel(false)
    }

    /** 清理某个请求的全部看门狗计时器。 */
    private fun cancelTimers(id: String) {
        cancelFirstTokenTimer(id)
        cancelIdleTimer(id)
    }

    // ---------- 内部（v2.0 流式） ----------

    /** 流开始：登记聚合状态，取消首 token 计时，启动空闲看门狗。 */
    private fun handleStreamBegin(frame: StreamBeginFrame) {
        streaming.putIfAbsent(frame.id, StringBuilder())
        if (frame.recognized != null) {
            streamingRecognized[frame.id] = frame.recognized
        } else {
            streamingRecognized.remove(frame.id)
        }
        cancelFirstTokenTimer(frame.id)
        startIdleTimer(frame.id)
        onStreamBegin?.invoke(frame.id, frame.recognized)
    }

    /** 流增量：追加到聚合缓冲，重置空闲看门狗。流已结束时忽略迟到的 delta。 */
    private fun handleStreamDelta(frame: StreamDeltaFrame) {
        val sb = streaming[frame.id] ?: return // 请求已结束（idle 超时/断流），忽略迟到 delta
        sb.append(frame.delta)
        resetIdleTimer(frame.id)
        onStreamDelta?.invoke(frame.id, frame.delta)
    }

    /** 流结束：ok=true 以 data.text 为准完成；ok=false 以已收 delta + 错误完成。 */
    private fun handleStreamEnd(frame: StreamEndFrame) {
        cancelIdleTimer(frame.id)
        val fut = pending.remove(frame.id)
        if (fut == null) {
            // 请求已被 idle 超时/断流结束，迟到的 stream_end 忽略（防重复 onReply/UI 气泡）
            streaming.remove(frame.id)
            streamingRecognized.remove(frame.id)
            return
        }
        val sb = streaming.remove(frame.id)
        val recognized = streamingRecognized.remove(frame.id)
        val accumulated = sb?.toString()
        if (frame.ok) {
            // data.text 为完整回复，可修复丢帧；缺失时回退到 delta 拼接
            val full = frame.text ?: accumulated ?: ""
            // 先回调后 complete：保证阻塞返回时所有流式回调已执行完（调用方可直接读状态）
            onStreamEnd?.invoke(frame.id, true, full)
            onReply?.invoke(full)
            if (!fut.isDone) {
                fut.complete(ResponseFrame(frame.id, true, full, recognized, null, null))
            }
        } else {
            val code = frame.errorCode ?: "llm_error"
            val msg = frame.errorMessage ?: "流式生成失败"
            onStreamEnd?.invoke(frame.id, false, accumulated)
            if (!fut.isDone) {
                fut.completeExceptionally(FairyVoiceException.AskError(code, msg))
            }
        }
    }

    private fun resolvePending(frame: ResponseFrame) {
        cancelTimers(frame.id)
        val fut = pending.remove(frame.id) ?: return
        if (!fut.isDone) {
            fut.complete(frame)
            onReply?.invoke(frame.text ?: "")
        }
    }

    /** 清理单个请求的挂起/流式状态与看门狗（发送失败 / 超时 / 中断时）。 */
    private fun cleanupRequest(id: String) {
        cancelTimers(id)
        pending.remove(id)
        streaming.remove(id)
        streamingRecognized.remove(id)
    }

    /**
     * 断线/停止时清理所有挂起请求。
     * 已开始流式但未收 stream_end 的：以已收 delta 拼接 + 标记「已中断」，视为可读结果完成，
     * 保证流中断时已收内容完整可用；其余未开始的直接失败。
     */
    private fun failAllPending(reason: FairyVoiceException) {
        firstTokenTimers.values.forEach { it.cancel(false) }
        firstTokenTimers.clear()
        idleTimers.values.forEach { it.cancel(false) }
        idleTimers.clear()
        streaming.forEach { (id, sb) ->
            val fut = pending.remove(id)
            streamingRecognized.remove(id)
            val partial = sb.toString()
            // 先回调后 complete：阻塞返回时 onStreamEnd 已触发
            onStreamEnd?.invoke(id, false, partial)
            if (fut != null && !fut.isDone) {
                fut.complete(ResponseFrame(id, true, partial + "\n\n（已中断）", null, null, null))
            }
        }
        streaming.clear()
        pending.values.forEach { fut ->
            if (!fut.isDone) fut.completeExceptionally(reason)
        }
        pending.clear()
    }
}
