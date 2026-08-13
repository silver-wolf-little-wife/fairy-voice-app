// SPDX-License-Identifier: AGPL-3.0-only
/**
 * 主界面：B 端连接配置 + 状态 + M3 联调（手动输入指令触发 ask）+ 唤醒引导。
 */
package com.fairyvoice.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.fairyvoice.app.protocol.FairyVoiceException
import com.fairyvoice.app.service.ConnectionService
import com.fairyvoice.app.util.Prefs
import com.fairyvoice.app.wake.FairyVoiceTileService
import com.fairyvoice.app.wake.WakeTrigger
import com.fairyvoice.app.wake.WakeKeyService

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvReply: TextView
    private lateinit var etServerUrl: EditText
    private lateinit var etToken: EditText
    private lateinit var etDeviceId: EditText
    private lateinit var etHeartbeat: EditText
    private lateinit var etAskInput: EditText

    /**
     * 用户已点击「启动连接」但 ConnectionService 尚未创建 client 时（startForegroundService
     * 异步启动），状态也显示「连接中」，避免点击后仍显示「未连接」的误导。
     */
    private var startRequested = false

    /** 前台时每 2s 轮询连接状态，让「连接中 → 已连接/失败」自动刷新，无需重进页面。 */
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusPoll = object : Runnable {
        override fun run() {
            refreshStatus()
            statusHandler.postDelayed(this, STATUS_POLL_MS)
        }
    }

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WakeTrigger.ACTION_FAIRY_WAKE) {
                runOnUiThread { handleWake() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvReply = findViewById(R.id.tvReply)
        etServerUrl = findViewById(R.id.etServerUrl)
        etToken = findViewById(R.id.etToken)
        etDeviceId = findViewById(R.id.etDeviceId)
        etHeartbeat = findViewById(R.id.etHeartbeat)
        etAskInput = findViewById(R.id.etAskInput)

        loadPrefsIntoUi()

        findViewById<Button>(R.id.btnStart).setOnClickListener { onStartClick() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { onStopClick() }
        findViewById<Button>(R.id.btnSendAsk).setOnClickListener { onSendAskClick() }
        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnAddTile).setOnClickListener { onAddTileClick() }

        requestNotificationPermissionIfNeeded()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        // Android 14+ (targetSdk 34) 动态注册广播必须显式声明 exported 标志，
        // 否则抛 SecurityException 导致启动即崩。ACTION_FAIRY_WAKE 为应用内广播，用 NOT_EXPORTED。
        val filter = IntentFilter(WakeTrigger.ACTION_FAIRY_WAKE)
        ContextCompat.registerReceiver(
            this, wakeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        statusHandler.removeCallbacks(statusPoll)
        statusHandler.post(statusPoll)
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        statusHandler.removeCallbacks(statusPoll)
        runCatching { unregisterReceiver(wakeReceiver) }
    }

    /**
     * singleTask 模式：App 已在后台/栈内时，磁贴/通知栏唤醒走 onNewIntent。
     * 此时动态注册的 receiver 可能尚未就绪，直接在这里处理，保证唤醒稳定。
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == WakeTrigger.ACTION_FAIRY_WAKE) {
            handleWake()
        }
    }

    private fun handleWake() {
        etAskInput.requestFocus()
        Toast.makeText(this, "Fairy 已唤醒", Toast.LENGTH_SHORT).show()
    }

    // ---------- 事件 ----------

    private fun onStartClick() {
        saveUiToPrefs()
        startRequested = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
            )
        }
        ConnectionService.start(this)
        refreshStatus()
    }

    private fun onStopClick() {
        startRequested = false
        ConnectionService.stop(this)
        FairyClientHolder.clear()
        refreshStatus()
    }

    private fun onAddTileClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 请求系统弹出"添加磁贴到控制中心"入口
            TileService.requestListeningState(
                this,
                ComponentName(this, FairyVoiceTileService::class.java),
            )
        } else {
            Toast.makeText(
                this,
                "请手动添加：下拉控制中心 → 编辑 → 添加 Fairy Voice",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun onSendAskClick() {
        val text = etAskInput.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "先输入指令文本", Toast.LENGTH_SHORT).show()
            return
        }
        val client = FairyClientHolder.client
        if (client == null || !client.isConnected) {
            tvReply.text = "未连接 B 端，先启动连接"
            return
        }
        tvReply.text = "等待 Fairy 回复…"
        Thread {
            try {
                val resp = client.sendAsk(text, "zh-CN")
                runOnUiThread {
                    tvReply.text = if (resp.ok) {
                        "Fairy：${resp.text ?: ""}"
                    } else {
                        "错误 [${resp.errorCode ?: "unknown"}]：${resp.errorMessage ?: ""}"
                    }
                }
            } catch (e: FairyVoiceException) {
                runOnUiThread { tvReply.text = "失败：${e.message}" }
            }
        }.start()
    }

    // ---------- 配置 ----------

    private fun loadPrefsIntoUi() {
        val p = Prefs.get(this)
        etServerUrl.setText(p.getString(Prefs.KEY_SERVER_URL, ""))
        etToken.setText(p.getString(Prefs.KEY_TOKEN, ""))
        etDeviceId.setText(p.getString(Prefs.KEY_DEVICE_ID, "android-phone"))
        etHeartbeat.setText((p.getLong(Prefs.KEY_HEARTBEAT_MS, 15_000L) / 1000).toString())
    }

    private fun saveUiToPrefs() {
        val p = Prefs.get(this).edit()
        p.putString(Prefs.KEY_SERVER_URL, etServerUrl.text.toString().trim())
        p.putString(Prefs.KEY_TOKEN, etToken.text.toString().trim())
        p.putString(Prefs.KEY_DEVICE_ID, etDeviceId.text.toString().trim().ifEmpty { "android-phone" })
        p.putLong(
            Prefs.KEY_HEARTBEAT_MS,
            (etHeartbeat.text.toString().toLongOrNull() ?: 15L).coerceAtLeast(1L) * 1000,
        )
        p.apply()
    }

    private fun refreshStatus() {
        val client = FairyClientHolder.client
        tvStatus.text = when {
            client?.isConnected == true -> getString(R.string.status_connected)
            client != null || startRequested -> getString(R.string.status_connecting)
            else -> getString(R.string.status_disconnected)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
            )
        }
    }

    companion object {
        private const val STATUS_POLL_MS = 2_000L
    }
}
