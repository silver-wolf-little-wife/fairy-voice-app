// SPDX-License-Identifier: AGPL-3.0-only
/**
 * 实体键唤醒：无障碍服务全局监听音量键组合。
 *
 * 默认组合：音量上 + 音量下 同时按下 0.5 秒（亮屏时任意界面生效）。
 * 不消费按键事件（onKeyEvent 恒返回 false），音量调节不受影响。
 *
 * 注：电源键（KEYCODE_POWER）事件由 Android 系统独占，第三方 APP 无法监听，
 * 因此"电源键+音量上"无法由 APP 实现；支持该映射的 ROM 请走系统快捷键
 * （见 docs/WAKEUP.md）。无系统映射的 ROM（如 OPPO ColorOS）使用控制中心磁贴。
 */
package com.fairyvoice.app.wake

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.fairyvoice.app.MainActivity
import com.fairyvoice.app.OneBotHolder
import com.fairyvoice.app.audio.VoiceController
import com.fairyvoice.app.service.FairyOverlayService
import com.fairyvoice.app.util.LogFile
import com.fairyvoice.app.util.Prefs

/**
 * 统一唤醒入口：磁贴、无障碍服务、通知栏按钮共用。
 *
 * M4-1.2 起：唤醒 = 不拉起全屏 App，直接启动 FairyOverlayService
 * （悬浮窗胶囊/流体云胶囊 + 录音 + AI 回复卡片）。
 * 缺 RECORD_AUDIO 权限（仅首次）→ 拉起 MainActivity 走授权，授权后继续。
 *
 * M4-1.3.1：先判定当前状态——录音中则停止录音（「再按一次停止」语义）。
 * M4-1.3.2：启动路径确定性分流，修复冷启动「假装录音」（显示录音中但实际
 * 未录音、未连接 B 端）——
 * - ConnectionService 常驻（FairyClientHolder.client 非空）→ 直接向
 *   FairyOverlayService 发 WAKE（服务已在前台，不闪界面）；
 * - 冷启动/未连接 → 拉起 MainActivity（自动连接 B 端 + 唤醒录音），
 *   不再依赖 TileService 等非豁免组件冷启动 FGS（Android 12+ 必受限）。
 */
object WakeTrigger {
    const val ACTION_FAIRY_WAKE = "com.fairyvoice.app.action.WAKE"
    /** FairyOverlayService 内「开始唤醒」action。 */
    const val ACTION_FAIRY_OVERLAY_WAKE = "com.fairyvoice.app.action.OVERLAY_WAKE"

    /** 拉起主界面用的 Intent：带 WAKE action，冷启动 onCreate / 热启动 onNewIntent 都能识别。 */
    fun wakeIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_FAIRY_WAKE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    /**
     * 统一触发（磁贴/实体键/通知栏共用）：
     * 1. 录音中 → 停止录音（再按一次停止）；
     * 2. 空闲且有 RECORD_AUDIO 权限、ConnectionService 常驻 → 直达 FairyOverlayService 录音；
     * 3. 空闲且冷启动/未连接 → 拉起 MainActivity（自动连接 B 端 + 唤醒录音）；
     * 4. 无权限 → 拉起 MainActivity 请求授权（授权后 onRequestPermissionsResult 继续）。
     */
    fun trigger(context: Context) {
        LogFile.d("wake.trigger state=${VoiceController.currentState}")
        // M4-1.3.1：再按一次 = 停止录音
        if (VoiceController.currentState == VoiceController.State.RECORDING) {
            LogFile.d("wake.trigger -> stopRecording")
            VoiceController.stopRecording()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            LogFile.d("wake.trigger perm=false -> MainActivity(auth)")
            context.startActivity(wakeIntent(context))
            return
        }
        // M4-1.3.2：连接常驻中 → 服务直达（不闪界面、不经过 MainActivity）
        if (OneBotHolder.client != null) {
            try {
                val intent = Intent(context, FairyOverlayService::class.java).apply {
                    action = ACTION_FAIRY_OVERLAY_WAKE
                }
                LogFile.d("wake.trigger client=alive -> OverlayService")
                ContextCompat.startForegroundService(context, intent)
                return
            } catch (e: Exception) {
                LogFile.e("wake.trigger FGS fail: ${e.message} -> MainActivity")
            }
        }
        // 冷启动 / 未连接：拉起 MainActivity，由其自动连接 B 端并触发唤醒
        LogFile.d("wake.trigger -> MainActivity(WAKE)")
        context.startActivity(wakeIntent(context))
    }
}

class WakeKeyService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val pressed = mutableSetOf<Int>()
    private var checkTask: Runnable? = null
    private var lastTriggerAt = 0L

    companion object {
        const val WAKE_HOLD_MS = 500L
        const val WAKE_DEBOUNCE_MS = 2_000L
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                pressed.add(code)
                scheduleCheck()
            }
            KeyEvent.ACTION_UP -> {
                pressed.remove(code)
                cancelCheck()
            }
        }
        // 不消费事件，音量键行为保持系统默认
        return false
    }

    private fun scheduleCheck() {
        cancelCheck()
        checkTask = Runnable {
            val now = System.currentTimeMillis()
            val combo = targetCombo()
            if (pressed.containsAll(combo) && combo.isNotEmpty() && now - lastTriggerAt >= WAKE_DEBOUNCE_MS) {
                lastTriggerAt = now
                WakeTrigger.trigger(this)
            }
        }
        handler.postDelayed(checkTask!!, WAKE_HOLD_MS)
    }

    private fun cancelCheck() {
        checkTask?.let { handler.removeCallbacks(it) }
        checkTask = null
    }

    /** 读取配置的唤醒组合（keyCode 列表）。默认 音量上+音量下。 */
    private fun targetCombo(): Set<Int> {
        val prefs = Prefs.get(this)
        return when (prefs.getString(Prefs.KEY_WAKE_COMBO, Prefs.COMBO_VOL_UP_DOWN)) {
            Prefs.COMBO_VOL_UP_LONG -> setOf(KeyEvent.KEYCODE_VOLUME_UP)
            else -> setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 仅用于按键监听，无需处理窗口事件
    }

    override fun onInterrupt() {
        pressed.clear()
        cancelCheck()
    }

    override fun onDestroy() {
        pressed.clear()
        cancelCheck()
        super.onDestroy()
    }
}
