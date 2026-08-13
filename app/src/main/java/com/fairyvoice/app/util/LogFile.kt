// SPDX-License-Identifier: AGPL-3.0-only
/**
 * M4-1.3.7：诊断日志——同时输出 logcat 与 filesDir/logs/fairy_log.txt，
 * 便于复现后通过「导出诊断日志」分享或 adb pull 定位问题。
 * 文件超过 256KB 自动清空重写，防长期膨胀。
 */
package com.fairyvoice.app.util

import android.util.Log
import com.fairyvoice.app.FairyVoiceApp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogFile {
    private const val TAG = "FairyVoice"
    private const val MAX_BYTES = 256 * 1024L
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun d(msg: String) {
        Log.d(TAG, msg)
        write(msg)
    }

    fun e(msg: String) {
        Log.e(TAG, msg)
        write(msg)
    }

    @Synchronized
    private fun write(msg: String) {
        try {
            val app = FairyVoiceApp.instance
            val dir = File(app.filesDir, "logs").apply { mkdirs() }
            val f = File(dir, "fairy_log.txt")
            if (f.length() > MAX_BYTES) f.delete()
            f.appendText("[${fmt.format(Date())}] $msg" + System.lineSeparator())
        } catch (_: Throwable) {
            // 日志失败不影响业务
        }
    }
}
