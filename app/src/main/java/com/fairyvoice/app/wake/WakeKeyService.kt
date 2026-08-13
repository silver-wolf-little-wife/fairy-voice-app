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
import com.fairyvoice.app.MainActivity
import com.fairyvoice.app.util.Prefs

/**
 * 统一唤醒入口：磁贴、无障碍服务、通知栏按钮共用。
 *
 * ⚠️ M4-1.1 修复：旧实现「startActivity + 动态广播」双通道，但 Intent 没带
 * ACTION_FAIRY_WAKE，导致 onNewIntent 识别不了（App 已在前台时点击无反应）；
 * 而广播在 App 冷启动时无人接收。现统一改为「Intent 带 action」驱动
 * MainActivity.onCreate/onNewIntent 直接触发录音，不再依赖广播。
 */
object WakeTrigger {
    const val ACTION_FAIRY_WAKE = "com.fairyvoice.app.action.WAKE"

    /** 唤醒主界面用的 Intent：带 WAKE action，保证冷启动 onCreate / 热启动 onNewIntent 都能识别。 */
    fun wakeIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_FAIRY_WAKE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    fun trigger(context: Context) {
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
