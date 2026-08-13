// SPDX-License-Identifier: AGPL-3.0-only
/**
 * 录音模块（M4-1）：AudioRecord 16kHz/16bit/单声道 → WAV 文件。
 *
 * - 专用单线程 executor 跑录音循环，start/stop 幂等。
 * - 先写 44 字节头占位，流式写 PCM，结束后回填 data size（见 WavHeader）。
 * - 默认 15 秒时长上限自动停止，防误触无限录。
 * - RECORD_AUDIO 权限由调用方保证（VoiceController / Activity 负责运行时请求）。
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
) {

    interface Callback {
        fun onStart()
        fun onStop(file: File)
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
            cb.onStop(file)
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
}
