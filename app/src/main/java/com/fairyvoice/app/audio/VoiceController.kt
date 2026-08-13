// SPDX-License-Identifier: AGPL-3.0-only
/**
 * 语音链路控制器（M4 起）：统一调度 录音 → ASR → ask → TTS 的状态机。
 *
 * M4-1 只实现「录音」段（IDLE ↔ RECORDING）。
 * M4-1.2 增加 [startWake] 唤醒链路：录音完成 → RECOGNIZING（ASR 未接，用占位文本）
 * → WAITING_AI（sendAsk）→ onReply / onError，支撑悬浮窗/流体云卡片显示 AI 回复。
 * M4-1.3 录音改静音自动停止（AudioRecorder 内置 VAD，说话结束 1.2s 自动停）；
 * 未检测到语音或录音过短视为无效唤醒，不再走 ask 链路。
 * RECOGNIZING / WAITING_AI / SPEAKING 为 M4-2 起的 ASR/TTS 预留。
 * 回调在录音/请求线程触发，Listener 实现方自行切主线程。
 *
 * M4-1.2：Listener 支持多播（MainActivity 与 FairyOverlayService 同时监听），
 * 使用 [addListener]/[removeListener]，不再用 setListener 单播。
 */
package com.fairyvoice.app.audio

import android.content.Context
import com.fairyvoice.app.FairyClientHolder
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
        fun onReply(text: String) {}
        fun onError(e: Exception)
    }

    /** M4-2 ASR 接入点：当前录音完成后直接发这条占位文本走 ask 链路，验证悬浮窗/卡片交互。 */
    const val ASR_PLACEHOLDER_TEXT = "你好"

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
            if (state != State.IDLE) return
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
                    if (autoAsk && hadSpeech && pcm >= MIN_SPEECH_BYTES.toLong()) {
                        askWithPlaceholder()
                    }
                }

                override fun onError(e: Exception) {
                    synchronized(lock) { recorder = null }
                    setState(State.IDLE)
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

    /** M4-1.2：录音完成 →（ASR 占位）→ sendAsk → onReply。未连接则报错。 */
    private fun askWithPlaceholder() {
        Thread {
            setState(State.RECOGNIZING)
            try {
                val client = FairyClientHolder.client
                if (client == null || !client.isConnected) {
                    throw FairyVoiceException.NotConnected("未连接 B 端，先启动连接")
                }
                setState(State.WAITING_AI)
                val resp = client.sendAsk(ASR_PLACEHOLDER_TEXT, "zh-CN")
                setState(State.IDLE)
                if (resp.ok) {
                    notifyReply(resp.text ?: "")
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

    private fun notifyReply(text: String) {
        for (l in listeners) l.onReply(text)
    }

    private fun notifyError(e: Exception) {
        for (l in listeners) l.onError(e)
    }

    private const val WAV_HEADER_SIZE = 44L
}
