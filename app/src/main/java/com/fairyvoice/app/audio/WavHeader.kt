// SPDX-License-Identifier: AGPL-3.0-only
/**
 * WAV 头生成（纯 Kotlin，无 Android 依赖，可在 JVM 单测）。
 *
 * 生成标准 44 字节 RIFF/WAVE 头（PCM，小端序），并支持录制结束后回填 data size。
 * 默认参数为 M4 规格：16kHz / 16bit / 单声道（可按需传参）。
 */
package com.fairyvoice.app.audio

object WavHeader {

    const val HEADER_SIZE = 44

    /**
     * 生成 WAV 文件头。
     * @param dataSize PCM 数据字节数（不含头）。录制中先写 0 占位，结束后用 [patch] 回填。
     */
    fun build(
        dataSize: Int,
        sampleRate: Int = 16000,
        channels: Int = 1,
        bitsPerSample: Int = 16,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val chunkSize = 36 + dataSize

        val b = ByteArray(HEADER_SIZE)
        var i = 0
        fun putAscii(s: String) {
            for (c in s) b[i++] = c.code.toByte()
        }

        fun putIntLE(v: Int) {
            b[i++] = (v and 0xFF).toByte()
            b[i++] = ((v shr 8) and 0xFF).toByte()
            b[i++] = ((v shr 16) and 0xFF).toByte()
            b[i++] = ((v shr 24) and 0xFF).toByte()
        }

        fun putShortLE(v: Int) {
            b[i++] = (v and 0xFF).toByte()
            b[i++] = ((v shr 8) and 0xFF).toByte()
        }

        putAscii("RIFF"); putIntLE(chunkSize); putAscii("WAVE")
        putAscii("fmt "); putIntLE(16)          // fmt chunk size
        putShortLE(1)                           // audio format: 1 = PCM
        putShortLE(channels)
        putIntLE(sampleRate)
        putIntLE(byteRate)
        putShortLE(blockAlign)
        putShortLE(bitsPerSample)
        putAscii("data"); putIntLE(dataSize)
        return b
    }

    /** 回填 data size（录制结束后把 PCM 字节数写进已写好的 44 字节头）。 */
    fun patch(header: ByteArray, dataSize: Int): ByteArray {
        require(header.size >= HEADER_SIZE) { "header too small" }
        val out = header.copyOf()
        fun writeIntLE(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte()
            out[off + 1] = ((v shr 8) and 0xFF).toByte()
            out[off + 2] = ((v shr 16) and 0xFF).toByte()
            out[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        writeIntLE(4, 36 + dataSize)   // RIFF chunk size
        writeIntLE(40, dataSize)       // data chunk size
        return out
    }
}
