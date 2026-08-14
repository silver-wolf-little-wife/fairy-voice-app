// SPDX-License-Identifier: AGPL-3.0-only
/**
 * 全局 Application：兜底捕获未处理异常，写入 crash_log.txt 便于现场排查。
 * 不吞异常，日志落盘后仍交给系统默认处理。
 */
package com.fairyvoice.app

import android.app.Application
import android.os.Build
import android.util.Log
import com.fairyvoice.app.audio.OnnxAsr
import com.fairyvoice.app.audio.VoiceController
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
        // P3：后台预加载本地 ASR 模型（78MB assets），避免首次语音识别长时间卡「识别中」
        Thread {
            runCatching { OnnxAsr.ensureLoaded(applicationContext) }
        }.start()
        // P4：预加载后调度空闲释放（30s 无语音则释放模型省内存；期间有语音会自动保持热）
        VoiceController.scheduleAsrRelease()
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
