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
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.Executors

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
    /** P3：异步广播线程（避免 listener 卡住阻塞识别→发送链路，如对话框 UI 处理）。 */
    private val broadcastExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "fairy-broadcast").apply { isDaemon = true }
    }

    private var recorder: AudioRecorder? = null
    private var player: MediaPlayer? = null
    private val lock = Any()

    // P4：空闲释放 ASR 模型（省内存）——语音结束后延迟释放，新语音取消
    private val asrReleaseHandler = Handler(Looper.getMainLooper())
    private var asrReleaseTask: Runnable? = null

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
                // P4：播报中再按 = 打断播报并重新录音
                if (state == State.SPEAKING) {
                    LogFile.d("VC.startRecording interrupt SPEAKING")
                    stopTts()
                    setState(State.IDLE)
                } else {
                    LogFile.d("VC.startRecording skipped state=$state")
                    return
                }
            }
            // P4：新语音取消空闲释放，模型保持热
            cancelAsrRelease()
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
                    // P4：录音结束调度空闲释放（30s 无新语音则释放 ASR 模型）
                    scheduleAsrRelease()
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
                LogFile.d("VC.ask asrDone=$text")
                // P3：识别完成立即通知 UI 显示用户气泡（不等 AI 回复）
                notifyRecognized(text)
                LogFile.d("VC.ask notified done")
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
                scheduleAsrRelease()
            } catch (e: Exception) {
                setState(State.IDLE)
                notifyError(e)
                scheduleAsrRelease()
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
        broadcast { it.onRecognized(text) }
    }

    private fun notifyReply(text: String, recognized: String?) {
        ChatHistory.add(text, ChatSender.FAIRY)
        broadcast { it.onReply(text, recognized) }
    }

    /**
     * P3：把当前 OneBotClient 的主动推送回调挂到 VoiceController（client 重建后需重新调用）。
     * 由 ConnectionService 在创建 client 后调用。
     */
    fun attachPush() {
        OneBotHolder.client?.onPush = { text -> notifyPush(text) }
    }

    /**
     * P4 TTS：把 OneBotClient 的 record 语音回调挂到 VoiceController（client 重建后需重新调用）。
     * 收到音频 → 播放 → SPEAKING → 播完 IDLE。
     */
    fun attachTts(context: Context) {
        val appCtx = context.applicationContext
        OneBotHolder.client?.onTts = { audioBytes ->
            playTts(appCtx, audioBytes)
        }
    }

    /** 播放 TTS 音频（后台线程）；播完/失败回 IDLE。 */
    private fun playTts(context: Context, audioBytes: ByteArray) {
        Thread {
            try {
                stopTts()
                setState(State.SPEAKING)
                val f = File(context.cacheDir, "fairy_tts_${System.currentTimeMillis()}.mp3")
                f.writeBytes(audioBytes)
                val mp = MediaPlayer()
                mp.setDataSource(f.absolutePath)
                mp.setOnCompletionListener {
                    runCatching { mp.release() }
                    f.delete()
                    player = null
                    setState(State.IDLE)
                }
                mp.setOnErrorListener { _, _, _ ->
                    runCatching { mp.release() }
                    f.delete()
                    player = null
                    setState(State.IDLE)
                    true
                }
                mp.prepare()
                mp.start()
                player = mp
                LogFile.d("VC.tts start ${audioBytes.size}B")
            } catch (e: Exception) {
                LogFile.e("VC.tts fail ${e.message}")
                setState(State.IDLE)
            }
        }.apply { isDaemon = true }.start()
    }

    /** P4：调度空闲释放 ASR 模型（延迟后 release，省内存）。语音结束/App 启动预加载后调用。 */
    fun scheduleAsrRelease() {
        asrReleaseTask?.let { asrReleaseHandler.removeCallbacks(it) }
        asrReleaseTask = Runnable {
            asrReleaseTask = null
            LogFile.d("VC.asr release (idle)")
            OnnxAsr.release()
        }
        asrReleaseHandler.postDelayed(asrReleaseTask!!, ASR_RELEASE_DELAY_MS)
    }

    private fun cancelAsrRelease() {
        asrReleaseTask?.let { asrReleaseHandler.removeCallbacks(it) }
        asrReleaseTask = null
    }

    /** 打断当前 TTS 播报。 */
    fun stopTts() {
        synchronized(lock) {
            player?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            player = null
        }
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

    /** P4：空闲释放 ASR 模型的延迟（30s 无新语音则释放）。 */
    private const val ASR_RELEASE_DELAY_MS = 30_000L

    private const val WAV_HEADER_SIZE = 44L
}
