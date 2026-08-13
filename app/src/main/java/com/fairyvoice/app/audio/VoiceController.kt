// SPDX-License-Identifier: AGPL-3.0-only
/**
 * 语音链路控制器（M4 起）：统一调度 录音 → ASR → ask → TTS 的状态机。
 *
 * M4-1 只实现「录音」段（IDLE ↔ RECORDING）；RECOGNIZING / WAITING_AI / SPEAKING
 * 为 M4-2 起的 ASR/TTS 预留。唤醒（WakeTrigger）与界面按钮统一走 [toggle]。
 * 回调在录音线程触发，Listener 实现方自行切主线程。
 */
package com.fairyvoice.app.audio

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VoiceController {

    enum class State { IDLE, RECORDING, RECOGNIZING, WAITING_AI, SPEAKING }

    interface Listener {
        fun onStateChanged(state: State)
        fun onRecorded(file: File)
        fun onError(e: Exception)
    }

    @Volatile
    private var state = State.IDLE

    @Volatile
    private var listener: Listener? = null

    private var recorder: AudioRecorder? = null
    private val lock = Any()

    val currentState: State get() = state

    fun setListener(l: Listener?) {
        listener = l
    }

    /** 唤醒/按钮触发：录音中则停止，否则开始录音。 */
    fun toggle(context: Context) {
        if (state == State.RECORDING) stopRecording() else startRecording(context)
    }

    /** 开始录音：生成时间戳文件名，落到 filesDir/recordings/。需已有 RECORD_AUDIO 权限。 */
    fun startRecording(context: Context) {
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

                override fun onStop(file: File) {
                    synchronized(lock) { recorder = null }
                    setState(State.IDLE)
                    listener?.onRecorded(file)
                }

                override fun onError(e: Exception) {
                    synchronized(lock) { recorder = null }
                    setState(State.IDLE)
                    listener?.onError(e)
                }
            })
            if (!started) setState(State.IDLE)
        }
    }

    /** 停止录音（异步收尾，onStop/onError 回调通知结果）。 */
    fun stopRecording() {
        synchronized(lock) { recorder?.stop() }
    }

    private fun setState(s: State) {
        state = s
        listener?.onStateChanged(s)
    }
}
