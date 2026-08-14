// SPDX-License-Identifier: AGPL-3.0-only
/**
 * 语音链路控制器（M4 起）：统一调度 录音 → ASR → ask → TTS 的状态机。
 *
 * M4-1 只实现「录音」段（IDLE ↔ RECORDING）。
 * M4-1.2 增加 [startWake] 唤醒链路。
 * M4-1.3 录音改静音自动停止（AudioRecorder 内置 VAD，说话结束 1.2s 自动停）；
 * 未检测到语音或录音过短视为无效唤醒，不再走 ask 链路。
 * M4-2 接入真实 ASR：录音完成 → RECOGNIZING（sendVoiceAsk，B 端 faster-whisper 识别）
 * → WAITING_AI（B 端 LLM）→ onReply(text, recognized)。SPEAKING 为 M4-3 TTS 预留。
 * 回调在录音/请求线程触发，Listener 实现方自行切主线程。
 *
 * M4-1.2：Listener 支持多播（MainActivity 与 FairyOverlayService 同时监听），
 * 使用 [addListener]/[removeListener]，不再用 setListener 单播。
 */
package com.fairyvoice.app.audio

import android.content.Context
import com.fairyvoice.app.ChatHistory
import com.fairyvoice.app.ChatSender
import com.fairyvoice.app.OneBotHolder
import com.fairyvoice.app.util.LogFile
import com.fairyvoice.app.protocol.OneBotException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object VoiceController {

    enum class State { IDLE, RECORDING, RECOGNIZING, WAITING_AI, SPEAKING }

    interface Listener {
        fun onStateChanged(state: State)
        fun onRecorded(file: File, hadSpeech: Boolean)
        /** P3：ASR 识别完成，识别文本已确定（此时即可显示用户气泡，不必等 AI 回复）。 */
        fun onRecognized(text: String) {}
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
            setState(State.RECOGNIZING)
            try {
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
                // P3：识别完成立即通知 UI 显示用户气泡（不等 AI 回复）
                notifyRecognized(text)
                // 等 OneBot 连接就绪
                var client = OneBotHolder.client
                var waited = 0L
                while ((client == null || !client.isConnected) && waited < CONNECT_WAIT_MS) {
                    Thread.sleep(200)
                    waited += 200
                    client = OneBotHolder.client
                }
                if (client == null || !client.isConnected) {
                    LogFile.e("VC.ask notConnected waited=$waited")
                    throw OneBotException.NotConnected("未连接 AstrBot，先启动连接")
                }
                setState(State.WAITING_AI)
                LogFile.d("VC.ask text=$text waited=$waited")
                val reply = client.sendPrivateMessage(text)
                setState(State.IDLE)
                LogFile.d("VC.ask reply len=${reply.length}")
                notifyReply(reply, text)
            } catch (e: Exception) {
                setState(State.IDLE)
                notifyError(e)
            }
        }.start()
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
        for (l in listeners) l.onRecognized(text)
    }

    private fun notifyReply(text: String, recognized: String?) {
        ChatHistory.add(text, ChatSender.FAIRY)
        for (l in listeners) l.onReply(text, recognized)
    }

    /**
     * P3：把当前 OneBotClient 的主动推送回调挂到 VoiceController（client 重建后需重新调用）。
     * 由 ConnectionService 在创建 client 后调用。
     */
    fun attachPush() {
        OneBotHolder.client?.onPush = { text -> notifyPush(text) }
    }

    private fun notifyPush(text: String) {
        ChatHistory.add(text, ChatSender.FAIRY)
        for (l in listeners) l.onPush(text)
    }

    private fun notifyError(e: Exception) {
        for (l in listeners) l.onError(e)
    }

    /** M4-1.3.2：ask 前等待 B 端连接就绪的上限。 */
    private const val CONNECT_WAIT_MS = 3_000L

    private const val WAV_HEADER_SIZE = 44L
}
