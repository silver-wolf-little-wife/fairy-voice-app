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
import com.fairyvoice.app.FairyClientHolder
import com.fairyvoice.app.util.LogFile
import com.fairyvoice.app.protocol.FairyVoiceException
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
        /** M4-2：AI 回复回调，recognized 为 B 端 ASR 识别文本（voice_ask 时非空）。 */
        fun onReply(text: String, recognized: String?) {}
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
                        askWithVoice(file)
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
    private fun askWithVoice(file: File) {
        Thread {
            setState(State.RECOGNIZING)
            try {
                var client = FairyClientHolder.client
                var waited = 0L
                while ((client == null || !client.isConnected) && waited < CONNECT_WAIT_MS) {
                    Thread.sleep(200)
                    waited += 200
                    client = FairyClientHolder.client
                }
                if (client == null || !client.isConnected) {
                    LogFile.e("VC.voiceAsk notConnected waited=$waited")
                    throw FairyVoiceException.NotConnected("未连接 B 端，先启动连接")
                }
                val wav = file.readBytes()
                if (wav.size < WAV_HEADER_SIZE.toInt()) {
                    throw FairyVoiceException.AskError("empty_audio", "录音文件无效")
                }
                // java.util.Base64（API 26+），纯 JVM 可用，避免 Android Base64 依赖
                val b64 = java.util.Base64.getEncoder().encodeToString(wav)
                setState(State.WAITING_AI)
                LogFile.d("VC.voiceAsk send wav=${wav.size}B waited=$waited")
                val resp = client.sendVoiceAsk(b64, "zh-CN")
                setState(State.IDLE)
                LogFile.d("VC.voiceAsk resp ok=${resp.ok} recognized=${resp.recognized} err=${resp.errorCode}")
                if (resp.ok) {
                    notifyReply(resp.text ?: "", resp.recognized)
                } else {
                    notifyError(FairyVoiceException.AskError(resp.errorCode ?: "unknown", resp.errorMessage ?: ""))
                }
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

    private fun notifyReply(text: String, recognized: String?) {
        for (l in listeners) l.onReply(text, recognized)
    }

    private fun notifyError(e: Exception) {
        for (l in listeners) l.onError(e)
    }

    /** M4-1.3.2：ask 前等待 B 端连接就绪的上限。 */
    private const val CONNECT_WAIT_MS = 3_000L

    private const val WAV_HEADER_SIZE = 44L
}
