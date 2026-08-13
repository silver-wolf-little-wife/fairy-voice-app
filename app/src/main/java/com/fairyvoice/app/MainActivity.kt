// SPDX-License-Identifier: AGPL-3.0-only
/**
 * 主界面：B 端连接配置 + 状态 + M3 联调（手动输入指令触发 ask）+ 唤醒引导。
 * M4-1：唤醒/按钮触发录音（VoiceController），运行时请求 RECORD_AUDIO 权限。
 * M4-1.1：唤醒改 Intent action 驱动（不再依赖动态广播），新增录音分享导出。
 */
package com.fairyvoice.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import androidx.core.content.FileProvider
import com.fairyvoice.app.audio.VoiceController
import com.fairyvoice.app.protocol.FairyVoiceException
import com.fairyvoice.app.service.ConnectionService
import com.fairyvoice.app.util.Prefs
import com.fairyvoice.app.wake.FairyVoiceTileService
import com.fairyvoice.app.wake.WakeTrigger
import com.fairyvoice.app.wake.WakeKeyService
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvReply: TextView
    private lateinit var tvVoiceState: TextView
    private lateinit var etServerUrl: EditText
    private lateinit var etToken: EditText
    private lateinit var etDeviceId: EditText
    private lateinit var etHeartbeat: EditText
    private lateinit var etAskInput: EditText
    private lateinit var btnRecordTest: Button
    private lateinit var btnShareRecord: Button

    /** 最近一次完成的录音文件（供「分享录音」导出）。 */
    private var lastRecordedFile: File? = null

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

    /** M4-1：录音状态/结果回显（回调在录音线程，统一切主线程）。 */
    private val voiceListener = object : VoiceController.Listener {
        override fun onStateChanged(state: VoiceController.State) {
            runOnUiThread { updateVoiceUi() }
        }

        override fun onRecorded(file: File) {
            runOnUiThread {
                lastRecordedFile = file
                // 16kHz/16bit/单声道 = 32000 B/s，44 字节为 WAV 头
                val dataSize = (file.length() - WAV_HEADER_SIZE).coerceAtLeast(0L)
                val seconds = dataSize / 32000.0
                val kb = file.length() / 1024.0
                tvReply.text = String.format(
                    Locale.US,
                    "录音完成：%s（%.1f 秒 / %.0f KB）\n点「分享录音」可导出到文件管理/微信等",
                    file.name, seconds, kb,
                )
                Toast.makeText(this@MainActivity, "录音已保存", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onError(e: Exception) {
            runOnUiThread {
                tvReply.text = "录音失败：${e.message}"
                Toast.makeText(this@MainActivity, "录音失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvReply = findViewById(R.id.tvReply)
        tvVoiceState = findViewById(R.id.tvVoiceState)
        etServerUrl = findViewById(R.id.etServerUrl)
        etToken = findViewById(R.id.etToken)
        etDeviceId = findViewById(R.id.etDeviceId)
        etHeartbeat = findViewById(R.id.etHeartbeat)
        etAskInput = findViewById(R.id.etAskInput)
        btnRecordTest = findViewById(R.id.btnRecordTest)
        btnShareRecord = findViewById(R.id.btnShareRecord)

        loadPrefsIntoUi()

        findViewById<Button>(R.id.btnStart).setOnClickListener { onStartClick() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { onStopClick() }
        findViewById<Button>(R.id.btnSendAsk).setOnClickListener { onSendAskClick() }
        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnAddTile).setOnClickListener { onAddTileClick() }
        btnRecordTest.setOnClickListener { onRecordTestClick() }
        btnShareRecord.setOnClickListener { onShareRecordClick() }

        VoiceController.setListener(voiceListener)
        updateVoiceUi()

        requestNotificationPermissionIfNeeded()
        refreshStatus()

        // 冷启动唤醒：由带 WAKE action 的 Intent 直接拉起（磁贴/通知栏/音量键），
        // 布局就绪后再触发录音；热启动（App 已在前台）走 onNewIntent。
        if (intent?.action == WakeTrigger.ACTION_FAIRY_WAKE) {
            statusHandler.post { handleWake() }
        }
    }

    override fun onResume() {
        super.onResume()
        statusHandler.removeCallbacks(statusPoll)
        statusHandler.post(statusPoll)
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        statusHandler.removeCallbacks(statusPoll)
    }

    override fun onDestroy() {
        VoiceController.setListener(null)
        super.onDestroy()
    }

    /**
     * singleTask 模式：App 已在栈内/前台时，磁贴/通知栏唤醒走 onNewIntent。
     * Intent 由 WakeTrigger.wakeIntent 构造（带 ACTION_FAIRY_WAKE），直接触发录音。
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == WakeTrigger.ACTION_FAIRY_WAKE) {
            handleWake()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                VoiceController.startRecording(this)
            } else {
                Toast.makeText(this, "未授予麦克风权限，无法录音", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** M4-1：唤醒 = 请求麦克风权限并开始/停止录音（再按一次停止）。 */
    private fun handleWake() {
        requestRecordPermissionAndToggle()
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

    /** M4-1：录音测试按钮（与唤醒同走 VoiceController.toggle）。 */
    private fun onRecordTestClick() {
        requestRecordPermissionAndToggle()
    }

    /** M4-1.1：分享最近一次录音（FileProvider 授权 URI，系统分享面板导出）。 */
    private fun onShareRecordClick() {
        val file = lastRecordedFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, getString(R.string.share_record_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_record_title)))
    }

    private fun requestRecordPermissionAndToggle() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            VoiceController.toggle(this)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), RC_RECORD_AUDIO
            )
        } else {
            VoiceController.toggle(this)
        }
    }

    /** 按当前状态刷新录音按钮文案与状态文本。 */
    private fun updateVoiceUi() {
        val s = VoiceController.currentState
        btnRecordTest.text = getString(
            if (s == VoiceController.State.RECORDING) R.string.btn_record_stop
            else R.string.btn_record_start
        )
        tvVoiceState.text = getString(
            when (s) {
                VoiceController.State.IDLE -> R.string.voice_state_idle
                VoiceController.State.RECORDING -> R.string.voice_state_recording
                VoiceController.State.RECOGNIZING -> R.string.voice_state_recognizing
                VoiceController.State.WAITING_AI -> R.string.voice_state_waiting_ai
                VoiceController.State.SPEAKING -> R.string.voice_state_speaking
            }
        )
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
        private const val RC_RECORD_AUDIO = 101
        private const val WAV_HEADER_SIZE = 44L
    }
}
