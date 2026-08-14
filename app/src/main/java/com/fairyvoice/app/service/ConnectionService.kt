// SPDX-License-Identifier: AGPL-3.0-only
/**
 * 常驻前台服务：持有 FairyVoiceClient，断线自动重连，通知栏显示状态。
 * 通知附带「唤醒」按钮，作为控制中心磁贴之外的又一个虚拟唤醒入口。
 * M3 阶段只做连接骨架；M4 在此挂接录音/识别/TTS 状态机。
 * M4-1.2：通知「唤醒」按钮改走 FairyOverlayService（不拉起全屏 App，直接录音）。
 */
package com.fairyvoice.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.fairyvoice.app.MainActivity
import com.fairyvoice.app.OneBotHolder
import com.fairyvoice.app.R
import com.fairyvoice.app.audio.VoiceController
import com.fairyvoice.app.protocol.OneBotClient
import com.fairyvoice.app.util.Prefs
import com.fairyvoice.app.wake.WakeTrigger

class ConnectionService : Service() {

    private var client: OneBotClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = Prefs.get(this)
        // P1：OneBot 直连 AstrBot（aiocqhttp 反向 WS，默认端口 6199）
        val astrbotUrl = p.getString(Prefs.KEY_ASTRBOT_URL, "ws://192.168.1.100:6199/ws") ?: ""
        val token = p.getString(Prefs.KEY_ASTRBOT_TOKEN, "") ?: ""
        val selfId = p.getString(Prefs.KEY_ONEBOT_SELF_ID, "10086") ?: "10086"
        val userId = p.getString(Prefs.KEY_ONEBOT_USER_ID, "10001") ?: "10001"

        client = OneBotHolder.createOrGet(astrbotUrl, token, selfId, userId)
        client?.start()
        // P3：把 OneBotClient 的主动推送回调挂到 VoiceController（息屏/对话页都能收到）
        VoiceController.attachPush()
        return START_STICKY
    }

    override fun onDestroy() {
        OneBotHolder.clear()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun startForegroundCompat() {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // M4-1.2：「唤醒」按钮不再拉起全屏 App，改为启动 FairyOverlayService 直接录音
        // （悬浮窗/流体云 + AI 回复卡片）。缺 RECORD_AUDIO 权限时服务内兜底拉起授权页。
        val wakePi = PendingIntent.getService(
            this, 1,
            Intent(this, FairyOverlayService::class.java).apply {
                action = WakeTrigger.ACTION_FAIRY_OVERLAY_WAKE
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .addAction(0, getString(R.string.notification_action_wake), wakePi)
            // M4-1.3.4：与 FairyOverlayService 前台通知同组折叠，避免通知栏出现两条常驻通知
            .setGroup("fairy_voice_service")
            .setGroupSummary(true)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "fairy_voice_conn"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, ConnectionService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
