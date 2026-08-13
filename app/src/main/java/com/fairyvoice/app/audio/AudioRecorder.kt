// SPDX-License-Identifier: AGPL-3.0-only
/**
 * 录音模块（M4-1）：AudioRecord 16kHz/16bit/单声道 → WAV 文件。
 *
 * - 专用单线程 executor 跑录音循环，start/stop 幂等。
 * - 先写 44 字节头占位，流式写 PCM，结束后回填 data size（见 WavHeader）。
 * - 默认 15 秒时长上限自动停止，防误触无限录。
 * - RECORD_AUDIO 权限由调用方保证（VoiceController / Activity 负责运行时请求）。
 *
 * M4-1.3（静音自动停止）：循环内按 50ms 分块计算 PCM RMS 能量，
 * 说话结束后连续静音 [silenceStopMs] 自动停止；录音启动后前 300ms 采样自适应噪声基线，
 * 阈值 = max(基线×3, 绝对下限)。未检测到语音前用更宽松的起始静音上限 [startSilenceMs]，
 * 避免唤醒后 1s 内开口被误停；最长时长 [maxDurationMs] 兜底保留。
 */
package com.fairyvoice.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder(
    private val sampleRate: Int = 16000,
    private val maxDurationMs: Long = 15_000L,
    /** 说话结束后连续静音多久自动停止。 */
    private val silenceStopMs: Long = 1_200L,
    /** 尚未检测到语音时的起始静音容忍上限（唤醒后开口延迟）。 */
    private val startSilenceMs: Long = 5_000L,
    /** 自适应基线采样时长（录音启动后前 N ms 视为环境噪声）。 */
    private val noiseCalibMs: Long = 300L,
) {

    interface Callback {
        fun onStart()

        /** [hadSpeech]：本次录音是否检测到语音（供上层决定是否继续走 ask 链路）。 */
        fun onStop(file: File, hadSpeech: Boolean)
        fun onError(e: Exception)
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "fairy-audio-rec").apply { isDaemon = true }
    }

    private val running = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private var callback: Callback? = null
    private var outputFile: File? = null

    val isRecording: Boolean get() = running.get()

    /** 启动录音（幂等：已在录则忽略并返回 false）。调用方需先取得 RECORD_AUDIO 权限。 */
    fun start(file: File, cb: Callback): Boolean {
        if (!running.compareAndSet(false, true)) return false
        outputFile = file
        callback = cb
        stopRequested.set(false)
        executor.execute { recordLoop() }
        return true
    }

    /** 请求停止（异步，录音线程读到标志后收尾）。未在录则忽略。 */
    fun stop() {
        if (running.get()) stopRequested.set(true)
    }

    /** 停止并释放线程池（一般仅测试/退出时调用）。 */
    fun shutdown() {
        stop()
        executor.shutdown()
    }

    private fun recordLoop() {
        val file = outputFile ?: return
        val cb = callback ?: return
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = maxOf(minBuf, sampleRate / 10) // 至少 100ms 缓冲
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize,
            )
        } catch (e: Exception) {
            fail(cb, e)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            fail(cb, IllegalStateException("AudioRecord init failed"))
            return
        }
        cb.onStart()

        // M4-1.3 静音检测状态：50ms/帧 @16kHz（提前到 try 外，收尾回调需要读取）
        val frameSamples = 800
        val frameBytes = frameSamples * 2
        val vad = VadState(frameBytes, noiseCalibMs, silenceStopMs, startSilenceMs)

        var dataSize = 0
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { fos ->
                val out = BufferedOutputStream(fos, bufSize)
                out.write(WavHeader.build(0, sampleRate)) // 头占位
                val buf = ByteArray(bufSize)
                record.startRecording()
                val startAt = System.currentTimeMillis()

                while (!stopRequested.get()) {
                    val n = record.read(buf, 0, buf.size)
                    if (n < 0) throw IOException("AudioRecord read failed: $n")
                    if (n > 0) {
                        out.write(buf, 0, n)
                        dataSize += n
                        var off = 0
                        while (off + frameBytes <= n) {
                            val rms = rms16(buf, off, frameSamples)
                            if (vad.update(rms, System.currentTimeMillis())) {
                                stopRequested.set(true)
                                break
                            }
                            off += frameBytes
                        }
                    }
                    if (System.currentTimeMillis() - startAt >= maxDurationMs) {
                        stopRequested.set(true)
                    }
                }
                out.flush()
            }
            // 回填 WAV 头 data size
            RandomAccessFile(file, "rw").use { raf ->
                raf.write(WavHeader.patch(WavHeader.build(0, sampleRate), dataSize))
            }
            running.set(false)
            cb.onStop(file, vad.hadSpeech)
        } catch (e: Exception) {
            running.set(false)
            fail(cb, e)
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    private fun fail(cb: Callback, e: Exception) {
        running.set(false)
        cb.onError(e)
    }

    /** 静音检测状态机（帧序推进，录音线程内独占）。 */
    private class VadState(
        private val frameBytes: Int,
        noiseCalibMs: Long,
        private val silenceStopMs: Long,
        private val startSilenceMs: Long,
    ) {
        private val calibFrames = ((noiseCalibMs / 50).coerceAtLeast(1)).toInt()
        private var frameCount = 0
        private var calibSum = 0.0
        private var noiseFloor = 0.0
        private var silenceMs = 0L
        private var lastFrameAt = 0L
        var hadSpeech = false
            private set

        /** 返回 true 表示应停止录音。 */
        fun update(rms: Int, now: Long): Boolean {
            if (lastFrameAt > 0) {
                val gap = now - lastFrameAt
                if (gap > 0) silenceMs += gap
            }
            lastFrameAt = now

            if (frameCount < calibFrames) {
                // 校准期：累加基线，不判静音
                calibSum += rms
                frameCount++
                if (frameCount == calibFrames) {
                    noiseFloor = calibSum / calibFrames
                }
                return false
            }

            val threshold = maxOf(noiseFloor * 3.0, ABS_MIN_RMS).toInt()
            if (rms >= threshold) {
                hadSpeech = true
                silenceMs = 0
            }
            val limit = if (hadSpeech) silenceStopMs else startSilenceMs
            return silenceMs >= limit
        }
    }

    /** 16bit 小端 PCM 的 RMS 能量。 */
    private fun rms16(buf: ByteArray, offset: Int, samples: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + samples * 2
        while (i < end) {
            val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            sum += s.toLong() * s
            i += 2
        }
        return kotlin.math.sqrt(sum.toDouble() / samples).toInt()
    }

    companion object {
        /** 静音判定绝对下限：16bit RMS 低于此值一律视为静音（极安静环境兜底）。 */
        private const val ABS_MIN_RMS = 300.0
    }
}
