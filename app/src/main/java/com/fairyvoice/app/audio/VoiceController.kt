// SPDX-License-Identifier: AGPL-3.0-only
/**
 * 语音链路控制器（M4 起）：统一调度 录音 → ASR → ask → TTS 的状态机。
 *
 * M4-1 只实现「录音」段（IDLE ↔ RECORDING）。
 * M4-1.2 增加 [startWake] 唤醒链路。
 * M4-1.3 录音改静音自动停止（AudioRecorder 内置 VAD，说话结束 1.2s 自动停）；
 * 未检测到语音或录音过短视为无效唤醒，不再走 ask 链路。
 * M4-2 接入真实 ASR：录音完成 → RECOGNIZING（本地 ASR 识别）
 * → WAITING_AI（B 端 LLM）→ onReply(text, recognized)。
 * S4（PLAN_STREAMING）：砍 TTS，状态机 = IDLE / RECORDING / RECOGNIZING / WAITING_AI。
 * 回调在录音/请求线程触发，Listener 实现方自行切主线程。
 *
 * M4-1.2：Listener 支持多播（MainActivity 与 FairyOverlayService 同时监听），
 * 使用 [addListener]/[removeListener]，不再用 setListener 单播。
 */
package com.fairyvoice.app.audio

import android.content.Context
import com.fairyvoice.app.ChatHistory
import com.fairyvoice.app.ChatSender
import com.fairyvoice.app.FairyClientHolder
import com.fairyvoice.app.util.LogFile
import com.fairyvoice.app.protocol.FairyVoiceException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

object VoiceController {

    enum class State { IDLE, RECORDING, RECOGNIZING, WAITING_AI }

    interface Listener {
        fun onStateChanged(state: State)
        fun onRecorded(file: File, hadSpeech: Boolean)
        /** P3：ASR 识别完成，识别文本已确定（此时即可显示用户气泡，不必等 AI 回复）。 */
        fun onRecognized(text: String) {}
        /** S3：AI 回复流开始（已创建流式消息，finalized=false）。 */
        fun onStreamBegin() {}
        /** S3：AI 回复增量，text 为累积文本（打字机效果驱动）。 */
        fun onStreamDelta(text: String) {}
        /** M4-2：AI 回复回调，recognized 为 B 端 ASR 识别文本（voice_ask 时非空）。 */
        fun onReply(text: String, recognized: String?) {}
        /** P3：AstrBot 主动推送的消息（无指令时下发）。 */
        fun onPush(text: String) {}
        fun onError(e: Exception)
    }

    /** 有效语音最小 PCM 时长（0.4s @16kHz/16bit 单声道），低于视为误触。 */
    private const val MIN_SPEECH_BYTES = 0.4 * 16000 * 2

    @Volatile
    private var state = State.IDLE

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** S3：当前进行中的流式回复消息 id（finalized=false），串行单用户场景下至多一个。 */
    @Volatile private var streamingMsgId: String? = null
    /** S3：当前流式回复已累积文本（stream_end 缺失完整文本时的兜底）。 */
    @Volatile private var streamingLastText: String = ""
    /** P3：异步广播线程（避免 listener 卡住阻塞识别→发送链路，如对话框 UI 处理）。 */
    private val broadcastExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "fairy-broadcast").apply { isDaemon = true }
    }

    private var recorder: AudioRecorder? = null
    private val lock = Any()

    val currentState: State get() = state

    fun addListener(l: Listener) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** 唤醒/按钮触发：录音中则停止，否则开始录音（纯录音，不自动 ask，录音测试按钮用）。 */
    fun toggle(context: Context) {
        if (state == State.RECORDING) stopRecording() else startRecording(context, autoAsk = false)
    }

    /** M4-1.2 唤醒链路：开始录音，检测到有效语音后自动走 ASR 占位文本 → ask → onReply。 */
    fun startWake(context: Context) {
        startRecording(context, autoAsk = true)
    }

    /** 开始录音：生成时间戳文件名，落到 filesDir/recordings/。需已有 RECORD_AUDIO 权限。 */
    fun startRecording(context: Context, autoAsk: Boolean) {
        synchronized(lock) {
            if (state != State.IDLE) {
                LogFile.d("VC.startRecording skipped state=$state")
                return
            }
            LogFile.d("VC.startRecording autoAsk=$autoAsk")
            val dir = File(context.filesDir, "recordings").apply { mkdirs() }
            val name = "fairy_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
            val file = File(dir, name)
            val rec = AudioRecorder()
            recorder = rec
            setState(State.RECORDING)
            val started = rec.start(file, object : AudioRecorder.Callback {
                override fun onStart() {
                    // 状态已在 startRecording 中置为 RECORDING
                }

                override fun onStop(file: File, hadSpeech: Boolean) {
                    synchronized(lock) { recorder = null }
                    setState(State.IDLE)
                    notifyRecorded(file, hadSpeech)
                    // M4-1.3：无语音 / 录音过短视为无效唤醒，不进 ask 链路
                    val pcm = file.length() - WAV_HEADER_SIZE
                    val goAsk = autoAsk && hadSpeech && pcm >= MIN_SPEECH_BYTES.toLong()
                    LogFile.d("VC.onStop speech=$hadSpeech pcm=$pcm goAsk=$goAsk")
                    if (goAsk) {
                        askWithVoice(context, file)
                    }
                }

                override fun onError(e: Exception) {
                    synchronized(lock) { recorder = null }
                    setState(State.IDLE)
                    LogFile.e("VC.onError ${e.message}")
                    notifyError(e)
                }
            })
            if (!started) setState(State.IDLE)
        }
    }

    /** 停止录音（异步收尾，onStop/onError 回调通知结果）。 */
    fun stopRecording() {
        synchronized(lock) { recorder?.stop() }
    }

    /**
     * M4-2：录音完成 → 读 WAV → base64 → sendVoiceAsk（B 端 ASR 识别 + AI 回复）
     * → onReply(text, recognized)。未连接则报错。
     * M4-1.3.2：冷启动自动连接后 B 端 WebSocket 可能尚未就绪，最多等待
     * [CONNECT_WAIT_MS] 再判定失败，避免「录音完但连接没跟上」的误报。
     */
    /**
     * P2：录音完成 → 本地 ASR（OnnxAsr）→ 文本 → OneBot 上报（OneBotClient.sendPrivateMessage）
     * → 收到 AstrBot 回复 → onReply(reply, recognized=识别文本)。
     * 识别失败 / 模型未就绪 / 未连接均走 onError。
     */
    private fun askWithVoice(context: Context, file: File) {
        Thread {
            var recognizedText: String? = null
            setState(State.RECOGNIZING)
            try {
                // P3：识别看门狗——10s 未离开「识别中」则强制恢复，防永久卡死
                val watchdog = Thread {
                    try {
                        Thread.sleep(ASR_TIMEOUT_MS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    if (VoiceController.currentState == State.RECOGNIZING) {
                        LogFile.e("VC.asr watchdog timeout -> force reset")
                        setState(State.IDLE)
                        notifyError(IllegalStateException("语音识别超时，请重试"))
                    }
                }.apply { isDaemon = true }
                watchdog.start()
                LogFile.d("VC.ask start modelLoaded=${OnnxAsr.isLoaded}")
                // 本地 ASR（懒加载模型）
                if (!OnnxAsr.ensureLoaded(context)) {
                    LogFile.e("VC.asr model not loaded")
                    throw IllegalStateException("本地识别模型加载失败，请查看日志")
                }
                val text = OnnxAsr.recognize(file)
                if (text.isNullOrBlank()) {
                    LogFile.d("VC.asr empty -> noSpeech")
                    throw IllegalStateException("未识别到语音，请重试")
                }
                recognizedText = text
                LogFile.d("VC.ask asrDone=$text")
                // P3：识别完成立即通知 UI 显示用户气泡（不等 AI 回复）
                notifyRecognized(text)
                LogFile.d("VC.ask notified done")
                // 等 B 端连接就绪
                var client = FairyClientHolder.client
                var waited = 0L
                while ((client == null || !client.isConnected) && waited < CONNECT_WAIT_MS) {
                    Thread.sleep(200)
                    waited += 200
                    client = FairyClientHolder.client
                }
                if (client == null || !client.isConnected) {
                    LogFile.e("VC.ask notConnected waited=$waited")
                    throw FairyVoiceException.NotConnected("未连接 B 端，先启动连接")
                }
                setState(State.WAITING_AI)
                LogFile.d("VC.ask text=$text waited=$waited")
                // S3：挂 FairyVoiceClient 流式回调（单用户串行，发起前设置安全），驱动流式消息增量上屏
                streamingMsgId = null
                streamingLastText = ""
                client.onStreamBegin = { _, _ ->
                    LogFile.d("VC.stream begin")
                    beginStreaming()
                }
                client.onStreamDelta = { _, delta ->
                    onStreamDeltaAppend(delta)
                }
                client.onStreamEnd = { _, ok, full ->
                    LogFile.d("VC.stream end ok=$ok fullLen=${full?.length}")
                    onStreamEnded(full, text)
                }
                // 阻塞返回完整文本；UI 由上方回调驱动，返回值仅兜底日志
                val resp = client.sendAsk(text)
                LogFile.d("VC.ask done respOk=${resp.ok} textLen=${resp.text?.length}")
            } catch (e: Exception) {
                setState(State.IDLE)
                // 流式消息已开始但未收尾（首 token/整体超时、连接断开且未走回调）→ 以已收文本 + 失败标记收尾，避免消息悬挂
                val id = synchronized(lock) { streamingMsgId }
                if (id != null && recognizedText != null) {
                    LogFile.e("VC.ask err during stream ${e.message}")
                    notifyReply(streamingLastText + "\n\n（失败：${e.message}）", recognizedText)
                } else {
                    notifyError(e)
                }
            }
        }.start()
    }

    // ---------- S3 流式消息辅助 ----------

    /** 流开始：创建 FAIRY 流式消息（finalized=false），广播 onStreamBegin。 */
    private fun beginStreaming() {
        synchronized(lock) {
            if (streamingMsgId != null) return
            val m = ChatHistory.addStreaming(ChatSender.FAIRY)
            streamingMsgId = m.id
            streamingLastText = ""
        }
        broadcast { it.onStreamBegin() }
    }

    /** 流增量：追加到当前流式消息，广播累积文本（打字机驱动）。 */
    private fun onStreamDeltaAppend(delta: String) {
        val id = synchronized(lock) { streamingMsgId }
        if (id == null) return
        ChatHistory.appendStreaming(id, delta)
        val cur = ChatHistory.messages.firstOrNull { it.id == id }?.text ?: return
        streamingLastText = cur
        broadcast { it.onStreamDelta(cur) }
    }

    /** 流结束：finalize 流式消息（data.text 完整兜底），广播最终回复。 */
    private fun onStreamEnded(fullText: String?, recognized: String?) {
        setState(State.IDLE)
        notifyReply(fullText ?: streamingLastText, recognized)
        streamingLastText = ""
    }

    private fun setState(s: State) {
        state = s
        for (l in listeners) l.onStateChanged(s)
    }

    private fun notifyRecorded(file: File, hadSpeech: Boolean) {
        for (l in listeners) l.onRecorded(file, hadSpeech)
    }

    private fun notifyRecognized(text: String) {
        // P3：写入全局历史，保证对话页未创建/后台时也记录（磁贴唤醒等场景）
        ChatHistory.add(text, ChatSender.USER)
        broadcast { it.onRecognized(text) }
    }

    private fun notifyReply(text: String, recognized: String?) {
        // 有进行中的流式消息 → finalize（不重复 add）；否则直接 add（非流式兜底）
        val id = synchronized(lock) { streamingMsgId }
        streamingMsgId = null
        if (id != null) {
            ChatHistory.finalizeStreaming(id, text)
        } else {
            ChatHistory.add(text, ChatSender.FAIRY)
        }
        broadcast { it.onReply(text, recognized) }
    }

    private fun notifyPush(text: String) {
        ChatHistory.add(text, ChatSender.FAIRY)
        broadcast { it.onPush(text) }
    }

    /** 异步广播到所有 listener（快照遍历 + 单线程，避免阻塞语音链路/顺序错乱）。 */
    private fun broadcast(block: (Listener) -> Unit) {
        val snapshot = ArrayList(listeners)
        broadcastExecutor.execute {
            for (l in snapshot) {
                runCatching { block(l) }
            }
        }
    }

    private fun notifyError(e: Exception) {
        for (l in listeners) l.onError(e)
    }

    /** M4-1.3.2：ask 前等待 B 端连接就绪的上限。 */
    private const val CONNECT_WAIT_MS = 3_000L

    /** P3：识别看门狗超时（防 ASR 卡死导致永久「识别中」）。 */
    private const val ASR_TIMEOUT_MS = 10_000L

    private const val WAV_HEADER_SIZE = 44L
}
