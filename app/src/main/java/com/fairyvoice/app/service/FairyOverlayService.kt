// SPDX-License-Identifier: AGPL-3.0-only
/**
 * M4-1.2 悬浮窗 / 流体云前台服务。
 *
 * 职责：
 * 1. 悬浮窗（SYSTEM_ALERT_WINDOW）：小布/Siri 式胶囊 + 展开卡片，显示录音状态与 AI 回复；
 * 2. Android 16 QPR1+ Live Updates（ColorOS 16 流体云）：状态/回复以 Live Update 通知呈现，
 *    系统自动以状态栏胶囊 + 展开卡片形态展示（点击卡片即回复文字），非 QPR1 设备降级普通通知；
 * 3. 唤醒链路：ACTION_FAIRY_OVERLAY_WAKE → VoiceController.startWake（录音 → 占位 ASR → ask → 回复）。
 *
 * M4-1.3：新增「胶囊形态」配置（Prefs.KEY_OVERLAY_MODE），悬浮窗 / 流体云二选一，
 * 避免唤醒时两个胶囊同时出现：
 * - overlay：只显示自绘悬浮胶囊 + 卡片，不发布 Live Update 状态通知；
 * - live：只发布 Live Update（系统胶囊 + 卡片），不显示悬浮窗；
 * - auto：有悬浮窗权限 → 悬浮窗，否则流体云（默认）。
 *
 * 服务随「启动连接」常驻（前台场景启动，规避后台启动 FGS 限制）；空闲时仅保留前台通知，
 * 不显示悬浮球；录音/回复期间才按所选形态显示胶囊并发布 Live Update 通知。
 */
package com.fairyvoice.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.fairyvoice.app.MainActivity
import com.fairyvoice.app.R
import com.fairyvoice.app.audio.VoiceController
import com.fairyvoice.app.util.Prefs
import com.fairyvoice.app.wake.WakeTrigger
import java.io.File

class FairyOverlayService : Service() {

    private var wm: WindowManager? = null
    private var overlayRoot: View? = null
    private var capsule: TextView? = null
    private var card: LinearLayout? = null
    private var cardText: TextView? = null
    private var lastReply: String? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private var hideTask: Runnable? = null

    /** 悬浮窗拖动起点。 */
    private var downRawX = 0f
    private var downRawY = 0f
    private var downLpX = 0
    private var downLpY = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // M4-1.3.3：启动即清理上次残留的流体云通知（服务被系统重启后无人 cancel 会滞留）
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFY_ID)
        ensureChannels()
        startForegroundCompat()
        VoiceController.addListener(voiceListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            WakeTrigger.ACTION_FAIRY_OVERLAY_WAKE -> handleWake()
            ACTION_HIDE -> hideAll()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        VoiceController.removeListener(voiceListener)
        hideTask?.let { uiHandler.removeCallbacks(it) }
        removeOverlay()
        super.onDestroy()
    }

    // ---------- 胶囊形态（M4-1.3） ----------

    private fun readOverlayMode(): String =
        Prefs.get(this).getString(Prefs.KEY_OVERLAY_MODE, Prefs.OVERLAY_MODE_AUTO)
            ?: Prefs.OVERLAY_MODE_AUTO

    /** 是否显示自绘悬浮胶囊。 */
    private fun shouldShowOverlay(): Boolean = when (readOverlayMode()) {
        Prefs.OVERLAY_MODE_OVERLAY -> true
        Prefs.OVERLAY_MODE_LIVE -> false
        else -> Settings.canDrawOverlays(this)
    }

    /** 是否发布 Live Update（流体云）状态通知。 */
    private fun shouldPublishLive(): Boolean = when (readOverlayMode()) {
        Prefs.OVERLAY_MODE_LIVE -> true
        Prefs.OVERLAY_MODE_OVERLAY -> false
        else -> !Settings.canDrawOverlays(this)
    }

    // ---------- 唤醒 / 隐藏 ----------

    private fun handleWake() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // 权限缺失（正常由 WakeTrigger 拦在前）：拉起授权页
            startActivity(WakeTrigger.wakeIntent(this))
            return
        }
        // M4-1.3：按所选形态显示，二选一
        if (shouldShowOverlay()) showOverlay()
        VoiceController.startWake(this)
    }

    private fun hideAll() {
        hideTask?.let { uiHandler.removeCallbacks(it) }
        hideTask = null
        lastReply = null
        removeOverlay()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFY_ID)
        // M4-1.3.3：部分 ROM（ColorOS 流体云）取消 Live Update 有延迟，300ms 后二次兜底
        uiHandler.postDelayed({ nm.cancel(NOTIFY_ID) }, 300)
    }

    // ---------- VoiceController 回调（录音线程 → 切主线程） ----------

    private val voiceListener = object : VoiceController.Listener {
        override fun onStateChanged(state: VoiceController.State) {
            uiHandler.post {
                val text = when (state) {
                    VoiceController.State.RECORDING -> getString(R.string.overlay_recording)
                    VoiceController.State.RECOGNIZING -> getString(R.string.overlay_recognizing)
                    VoiceController.State.WAITING_AI -> getString(R.string.overlay_waiting_ai)
                    VoiceController.State.SPEAKING -> getString(R.string.overlay_speaking)
                    VoiceController.State.IDLE -> {
                        // M4-1.3.1：状态结束清理 Live Update（防「录音中」残留卡死），
                        // 悬浮胶囊一并隐藏，回复卡片由 onReply/onError 接管
                        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFY_ID)
                        capsule?.visibility = View.GONE
                        return@post
                    }
                }
                capsule?.text = text
                if (shouldPublishLive()) updateLiveNotification(text, null)
            }
        }

        override fun onRecorded(file: File, hadSpeech: Boolean) {
            // startWake 内部继续走 ASR 占位 → ask，无需处理
        }

        override fun onReply(text: String) {
            uiHandler.post {
                lastReply = text
                showCard(text)
                if (shouldPublishLive()) updateLiveNotification(getString(R.string.overlay_reply_title), text)
                scheduleHide(REPLY_SHOW_MS)
            }
        }

        override fun onError(e: Exception) {
            uiHandler.post {
                showCard("${getString(R.string.overlay_error)}：${e.message}")
                if (shouldPublishLive()) updateLiveNotification(getString(R.string.overlay_error), e.message)
                scheduleHide(ERROR_SHOW_MS)
            }
        }
    }

    // ---------- 悬浮窗 ----------

    private fun showOverlay() {
        if (overlayRoot != null) return
        val root = LayoutInflater.from(this).inflate(R.layout.view_fairy_overlay, null)
        capsule = root.findViewById(R.id.overlayCapsule)
        card = root.findViewById(R.id.overlayCard)
        cardText = root.findViewById(R.id.overlayCardText)
        root.findViewById<TextView>(R.id.overlayBtnCopy).setOnClickListener { copyReply() }
        root.findViewById<TextView>(R.id.overlayBtnClose).setOnClickListener { hideAll() }
        capsule?.setOnClickListener { onCapsuleClick() }
        capsule?.let { makeDraggable(it) }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(OVERLAY_Y_DP)
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { wm?.addView(root, lp) }.onFailure { return }
        overlayRoot = root
        capsule?.text = getString(R.string.overlay_recording)
    }

    private fun removeOverlay() {
        overlayRoot?.let { runCatching { wm?.removeView(it) } }
        overlayRoot = null
        capsule = null
        card = null
        cardText = null
    }

    private fun showCard(text: String) {
        // M4-1.3：仅悬浮窗形态显示悬浮卡片；live 形态由流体云系统卡片承接
        if (!shouldShowOverlay()) return
        if (overlayRoot == null) showOverlay()
        cardText?.text = text
        capsule?.visibility = View.GONE
        card?.visibility = View.VISIBLE
    }

    private fun onCapsuleClick() {
        when (VoiceController.currentState) {
            VoiceController.State.RECORDING -> VoiceController.stopRecording()
            else -> lastReply?.let { showCard(it) }
        }
    }

    private fun copyReply() {
        val text = cardText?.text?.toString() ?: return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Fairy 回复", text))
        Toast.makeText(this, R.string.overlay_copied, Toast.LENGTH_SHORT).show()
    }

    /** 拖动胶囊：移动的是悬浮窗根布局的 WindowManager.LayoutParams。 */
    private fun makeDraggable(view: View) {
        view.setOnTouchListener { _, event ->
            val root = overlayRoot ?: return@setOnTouchListener false
            val lp = root.layoutParams as? WindowManager.LayoutParams
                ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downLpX = lp.x
                    downLpY = lp.y
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = downLpX + (event.rawX - downRawX).toInt()
                    lp.y = downLpY + (event.rawY - downRawY).toInt()
                    runCatching { wm?.updateViewLayout(root, lp) }
                    false
                }
                else -> false
            }
        }
    }

    // ---------- Live Updates（ColorOS 16 流体云） ----------

    private fun updateLiveNotification(title: String, body: String?) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val builder = Notification.Builder(this, CHANNEL_LIVE)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(title)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_DEFAULT)
            .setContentIntent(openAppPi())
        if (body != null) {
            builder.setStyle(Notification.BigTextStyle().bigText(body))
        }
        // Android 16 QPR1+（ColorOS 16 流体云双兼容）：提升为 Live Update，
        // 系统自动以状态栏胶囊形态展示，点胶囊展开卡片显示回复文本。
        if (Build.VERSION.SDK_INT >= 36 && nm.canPostPromotedNotifications()) {
            try {
                builder.setRequestPromotedOngoing(true)
            } catch (e: Throwable) {
                // API 36 初始版无此方法：降级普通通知，行为不变
            }
        }
        // M4-1.3.3：notify 异常不冒泡（ColorOS 流体云 promoted 通知异常会导致主线程崩溃、
        // 服务重启后流体云滞留且无人清理）
        runCatching { nm.notify(NOTIFY_ID, builder.build()) }
    }

    private fun openAppPi(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE,
        )

    // ---------- 前台通知 ----------

    private fun ensureChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CONN, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LIVE, getString(R.string.overlay_channel_live), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun startForegroundCompat() {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_CONN)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            // M4-1.3.4：与 ConnectionService 前台通知同组折叠（非汇总），避免两条常驻通知
            .setGroup("fairy_voice_service")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FG_NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(FG_NOTIFY_ID, notification)
        }
    }

    private fun scheduleHide(ms: Long) {
        hideTask?.let { uiHandler.removeCallbacks(it) }
        hideTask = Runnable { hideAll() }
        uiHandler.postDelayed(hideTask!!, ms)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL_CONN = "fairy_voice_conn"
        private const val CHANNEL_LIVE = "fairy_voice_live"
        // M4-1.3.3：与 ConnectionService(1001) 隔离，防止两个前台服务通知互相覆盖
        // 导致服务被系统误杀、流体云通知残留滞留
        private const val FG_NOTIFY_ID = 2001
        private const val NOTIFY_ID = 1002
        private const val OVERLAY_Y_DP = 140
        private const val REPLY_SHOW_MS = 20_000L
        private const val ERROR_SHOW_MS = 8_000L
        const val ACTION_HIDE = "com.fairyvoice.app.action.OVERLAY_HIDE"

        /** 随「启动连接」常驻（无 action，仅前台通知待命）。 */
        fun ensureRunning(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, FairyOverlayService::class.java)
            )
        }

        /** 唤醒：启动/复用服务并触发录音链路。 */
        fun startWake(context: Context) {
            val i = Intent(context, FairyOverlayService::class.java).apply {
                action = WakeTrigger.ACTION_FAIRY_OVERLAY_WAKE
            }
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FairyOverlayService::class.java))
        }
    }
}

