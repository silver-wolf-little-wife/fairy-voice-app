// SPDX-License-Identifier: AGPL-3.0-only
/**
 * 全局 Application：兜底捕获未处理异常，写入 crash_log.txt 便于现场排查。
 * 不吞异常，日志落盘后仍交给系统默认处理。
 */
package com.fairyvoice.app

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FairyVoiceApp : Application() {

    companion object {
        @Volatile
        lateinit var instance: FairyVoiceApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val sb = StringBuilder()
                sb.append("=== Fairy Voice Crash ===\n")
                sb.append("time: ")
                    .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    .append("\n")
                sb.append("device: ")
                    .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                sb.append("android: ")
                    .append(Build.VERSION.RELEASE).append(" (API ")
                    .append(Build.VERSION.SDK_INT).append(")\n")
                sb.append("thread: ").append(thread.name).append('\n')
                sb.append(sw.toString()).append('\n')
                val file = File(filesDir, "crash_log.txt")
                file.appendText(sb.toString())
                Log.e("FairyVoice", sb.toString())
            } catch (_: Exception) {
                // 日志写入失败不再递归触发
            }
        }
    }
}
