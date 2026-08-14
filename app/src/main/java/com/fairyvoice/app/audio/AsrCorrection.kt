// SPDX-License-Identifier: AGPL-3.0-only
/**
 * ASR 后处理纠错（方案A）。
 *
 * 背景：paraformer-zh 对英文词「Fairy」识别弱，常误识别为同音「ferry」（或小写 fairy）。
 * 本类在 ASR 输出后做词边界纠正，保证唤醒词/称呼正确显示。
 *
 * 词边界用 `(?<![a-zA-Z])` / `(?![a-zA-Z])`（前后不是字母）：
 * - 不误伤英文单词内部（如 different、berry）；
 * - 中文夹英文也能命中（「坐ferry去」→「坐Fairy去」）。
 * 纯 Kotlin，可 JVM 单测。
 */
package com.fairyvoice.app.audio

object AsrCorrection {

    private val RULE = Regex("(?<![a-zA-Z])(?:[Ff]erry|[Ff]airy)(?![a-zA-Z])")

    /** 把同音误识别（ferry/fairy）纠正为 Fairy；无命中时原样返回。 */
    fun correct(text: String): String = RULE.replace(text, "Fairy")
}
