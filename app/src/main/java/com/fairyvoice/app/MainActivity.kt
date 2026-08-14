// SPDX-License-Identifier: AGPL-3.0-only
/**
 * P3 主界面重构：底部导航双页面宿主（对话 ChatFragment / 设置 SettingsFragment）。
 *
 * 保留的全局职责：
 * - 底部导航切换 Fragment
 * - 唤醒链路（音量键/磁贴/通知栏 → ACTION_FAIRY_WAKE → 录音）：冷启动 onCreate / 热启动 onNewIntent
 * - RECORD_AUDIO 权限请求（Activity 上下文弹框，授权后继续唤醒）
 * - 打开 App 自动连接 AstrBot
 */
package com.fairyvoice.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.fairyvoice.app.service.ConnectionService
import com.fairyvoice.app.service.FairyOverlayService
import com.fairyvoice.app.util.LogFile
import com.fairyvoice.app.util.Prefs
import com.fairyvoice.app.wake.WakeTrigger
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var chatFragment: ChatFragment
    private lateinit var settingsFragment: SettingsFragment

    /** M4-1.2：RECORD_AUDIO 授权后继续唤醒链路。 */
    private var pendingWake = false

    /** M4-1.3.6：冷启动/后台复用唤醒——窗口就绪（获焦）提前触发，超时兜底。 */
    private var wakeTask: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // M4-1.3：双保险隐藏最近任务（Manifest excludeFromRecents 已设）
        intent?.addFlags(0x08000000 /* FLAG_EXCLUDE_FROM_RECENTS */)
        setContentView(R.layout.activity_main)

        chatFragment = ChatFragment()
        settingsFragment = SettingsFragment()
        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, settingsFragment, "settings")
            .add(R.id.fragmentContainer, chatFragment, "chat")
            .hide(settingsFragment)
            .commit()

        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chat -> showFragment(chatFragment)
                R.id.nav_settings -> showFragment(settingsFragment)
            }
            true
        }

        // M4-1.3.2：打开即自动尝试连接 B 端（磁贴冷启动 / 桌面图标统一）
        mainHandler.post { autoConnectIfNeeded() }

        LogFile.d("Main.onCreate action=${intent?.action}")
        // M4-1.3.6：冷启动唤醒挂起，窗口就绪后触发，500ms 延迟兜底
        if (intent?.action == WakeTrigger.ACTION_FAIRY_WAKE) {
            scheduleWake()
        }
    }

    override fun onResume() {
        super.onResume()
        LogFile.d("Main.onResume focus=${hasWindowFocus()}")
        if (hasWindowFocus()) fireWakeNow()
    }

    /** M4-1.3.6：窗口就绪（获焦）即提前执行待处理唤醒。 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        LogFile.d("Main.focus hasFocus=$hasFocus")
        if (hasFocus) fireWakeNow()
    }

    /** singleTask：App 已在栈内/前台时，唤醒走 onNewIntent。 */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.addFlags(0x08000000 /* FLAG_EXCLUDE_FROM_RECENTS */)
        LogFile.d("Main.onNewIntent action=${intent?.action} focus=${hasWindowFocus()}")
        if (intent?.action == WakeTrigger.ACTION_FAIRY_WAKE) {
            if (hasWindowFocus()) handleWake() else scheduleWake()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        LogFile.d("Main.permResult code=$requestCode granted=${grantResults.firstOrNull()}")
        if (requestCode == RC_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingWake) {
                    pendingWake = false
                    startWakeSafely()
                }
            } else {
                LogFile.d("Main.perm denied")
            }
        }
    }

    // ---------- 唤醒链路 ----------

    private fun handleWake() {
        LogFile.d("Main.handleWake")
        requestRecordPermissionAndToggle(wake = true)
    }

    private fun scheduleWake() {
        LogFile.d("Main.scheduleWake")
        wakeTask?.let { mainHandler.removeCallbacks(it) }
        wakeTask = Runnable {
            wakeTask = null
            LogFile.d("Main.wakeTask.fire")
            handleWake()
        }
        mainHandler.postDelayed(wakeTask!!, WAKE_DELAY_MS)
    }

    private fun fireWakeNow() {
        if (wakeTask != null) {
            LogFile.d("Main.fireWakeNow")
            wakeTask?.let { mainHandler.removeCallbacks(it) }
            wakeTask = null
            handleWake()
        }
    }

    private fun requestRecordPermissionAndToggle(wake: Boolean) {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        LogFile.d("Main.reqRecord wake=$wake granted=$granted")
        if (granted) {
            startWakeSafely()
        } else {
            pendingWake = wake
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), RC_RECORD_AUDIO
            )
        }
    }

    private fun startWakeSafely() {
        try {
            FairyOverlayService.startWake(this)
        } catch (e: Exception) {
            LogFile.e("Main.startWake fail ${e.message}")
        }
    }

    private fun autoConnectIfNeeded() {
        if (OneBotHolder.client?.isConnected == true) return
        val p = Prefs.get(this)
        val astrbotUrl = p.getString(Prefs.KEY_ASTRBOT_URL, "") ?: ""
        if (astrbotUrl.isBlank()) return
        ConnectionService.start(this)
        FairyOverlayService.ensureRunning(this)
    }

    private fun showFragment(f: Fragment) {
        val other = if (f === chatFragment) settingsFragment else chatFragment
        supportFragmentManager.beginTransaction()
            .show(f)
            .hide(other)
            .commit()
    }

    companion object {
        private const val RC_RECORD_AUDIO = 101
        private const val WAKE_DELAY_MS = 500L
    }
}
