// SPDX-License-Identifier: AGPL-3.0-only
package com.fairyvoice.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WavHeaderTest {

    private fun le16(h: ByteArray, off: Int) =
        (h[off].toInt() and 0xFF) or ((h[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(h: ByteArray, off: Int) =
        (h[off].toInt() and 0xFF) or ((h[off + 1].toInt() and 0xFF) shl 8) or
            ((h[off + 2].toInt() and 0xFF) shl 16) or ((h[off + 3].toInt() and 0xFF) shl 24)

    @Test
    fun `header is 44 bytes`() {
        assertEquals(44, WavHeader.build(0).size)
    }

    @Test
    fun `magic RIFF WAVE fmt data present`() {
        val h = WavHeader.build(0)
        assertEquals("RIFF", String(h, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(h, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(h, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(h, 36, 4, Charsets.US_ASCII))
    }

    @Test
    fun `default pcm mono 16k 16bit fields`() {
        val h = WavHeader.build(0)
        assertEquals(1, le16(h, 20))       // audio format = PCM
        assertEquals(1, le16(h, 22))       // channels
        assertEquals(16000, le32(h, 24))   // sample rate
        assertEquals(32000, le32(h, 28))   // byte rate = 16000 * 1 * 2
        assertEquals(2, le16(h, 32))       // block align
        assertEquals(16, le16(h, 34))      // bits per sample
        assertEquals(36, le32(h, 4))       // RIFF chunk size（空数据）
        assertEquals(0, le32(h, 40))       // data size 占位
    }

    @Test
    fun `custom params byteRate`() {
        val h = WavHeader.build(0, sampleRate = 44100, channels = 2, bitsPerSample = 16)
        assertEquals(44100, le32(h, 24))
        assertEquals(44100 * 2 * 2, le32(h, 28))
        assertEquals(2, le16(h, 22))
    }

    @Test
    fun `dataSize stored little endian`() {
        val h = WavHeader.build(0x01020304)
        assertEquals(0x01020304, le32(h, 40))
        assertEquals(36 + 0x01020304, le32(h, 4))
    }

    @Test
    fun `patch backfills dataSize`() {
        val placeholder = WavHeader.build(0)
        val patched = WavHeader.patch(placeholder, 12345)
        assertArrayEquals(WavHeader.build(12345), patched)
    }
}
