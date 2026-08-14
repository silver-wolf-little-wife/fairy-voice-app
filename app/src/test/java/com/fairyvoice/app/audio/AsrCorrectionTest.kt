// SPDX-License-Identifier: AGPL-3.0-only
package com.fairyvoice.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AsrCorrectionTest {

    @Test
    fun `ferry 纠正为 Fairy`() {
        assertEquals("Fairy 今天天气", AsrCorrection.correct("ferry 今天天气"))
    }

    @Test
    fun `小写 fairy 纠正为 Fairy`() {
        assertEquals("Fairy 帮我打开灯", AsrCorrection.correct("fairy 帮我打开灯"))
    }

    @Test
    fun `中文夹英文也命中`() {
        assertEquals("坐Fairy去对岸", AsrCorrection.correct("坐ferry去对岸"))
    }

    @Test
    fun `已是大写 Fairy 保持不变`() {
        assertEquals("Fairy 今天天气", AsrCorrection.correct("Fairy 今天天气"))
    }

    @Test
    fun `不误伤英文单词内部`() {
        assertEquals("different", AsrCorrection.correct("different"))
        assertEquals("berry", AsrCorrection.correct("berry"))
    }

    @Test
    fun `句子尾部 ferry 也命中`() {
        assertEquals("叫Fairy", AsrCorrection.correct("叫ferry"))
    }

    @Test
    fun `纯中文不变`() {
        assertEquals("今天天气不错", AsrCorrection.correct("今天天气不错"))
    }
}
