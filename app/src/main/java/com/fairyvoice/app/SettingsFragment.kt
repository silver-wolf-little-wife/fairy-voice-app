// SPDX-License-Identifier: AGPL-3.0-only
/**
 * P3 设置调试页：AstrBot 连接配置 / 录音测试 / 分享 / 日志 / 手动发送 / 唤醒 / 磁贴 / 胶囊形态。
 * 原 MainActivity 的设置逻辑迁移至此。
 */
package com.fairyvoice.app

import android.Manifest
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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.fairyvoice.app.audio.VoiceController
import com.fairyvoice.app.protocol.FairyVoiceException
import com.fairyvoice.app.service.ConnectionService
import com.fairyvoice.app.service.FairyOverlayService
import com.fairyvoice.app.util.LogFile
import com.fairyvoice.app.util.Prefs
import com.fairyvoice.app.wake.FairyVoiceTileService
import java.io.File
import java.util.Locale

class SettingsFragment : Fragment() {

    private lateinit var tvStatus: TextView
    private lateinit var tvReply: TextView
    private lateinit var tvVoiceState: TextView
    private lateinit var etServerUrl: EditText
    private lateinit var etToken: EditText
    private lateinit var etSelfId: EditText
    private lateinit var etUserId: EditText
    private lateinit var etAskInput: EditText

    private var lastRecordedFile: File? = null
    private var startRequested = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val statusPoll = object : Runnable {
        override fun run() {
            refreshStatus()
            uiHandler.postDelayed(this, STATUS_POLL_MS)
        }
    }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                VoiceController.toggle(requireContext())
            } else {
                Toast.makeText(requireContext(), "未授予麦克风权限，无法录音", Toast.LENGTH_SHORT).show()
            }
        }

    private val voiceListener = object : VoiceController.Listener {
        override fun onStateChanged(state: VoiceController.State) {
            uiHandler.post { updateVoiceUi() }
        }

        override fun onRecorded(file: File, hadSpeech: Boolean) {
            uiHandler.post {
                lastRecordedFile = file
                val dataSize = (file.length() - WAV_HEADER_SIZE).coerceAtLeast(0L)
                val seconds = dataSize / 32000.0
                val kb = file.length() / 1024.0
                tvReply.text = String.format(
                    Locale.US,
                    "录音完成：%s（%.1f 秒 / %.0f KB）%s\n点「分享录音」可导出",
                    file.name, seconds, kb,
                    if (hadSpeech) "" else "（未检测到语音）",
                )
            }
        }

        override fun onReply(text: String, recognized: String?) {
            uiHandler.post {
                tvReply.text = if (recognized.isNullOrBlank()) {
                    "Fairy：$text"
                } else {
                    "识别：$recognized\nFairy：$text"
                }
            }
        }

        override fun onPush(text: String) {
            uiHandler.post { tvReply.text = "Fairy（推送）：$text" }
        }

        override fun onError(e: Exception) {
            uiHandler.post {
                tvReply.text = "语音指令失败：${e.message}"
                Toast.makeText(requireContext(), "语音指令失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v = inflater.inflate(R.layout.fragment_settings, container, false)
        tvStatus = v.findViewById(R.id.tvStatus)
        tvReply = v.findViewById(R.id.tvReply)
        tvVoiceState = v.findViewById(R.id.tvVoiceState)
        etServerUrl = v.findViewById(R.id.etServerUrl)
        etToken = v.findViewById(R.id.etToken)
        etSelfId = v.findViewById(R.id.etSelfId)
        etUserId = v.findViewById(R.id.etUserId)
        etAskInput = v.findViewById(R.id.etAskInput)

        v.findViewById<Button>(R.id.btnStart).setOnClickListener { onStartClick() }
        v.findViewById<Button>(R.id.btnStop).setOnClickListener { onStopClick() }
        v.findViewById<Button>(R.id.btnSendAsk).setOnClickListener { onSendAskClick() }
        v.findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        v.findViewById<Button>(R.id.btnAddTile).setOnClickListener { onAddTileClick() }
        v.findViewById<Button>(R.id.btnRecordTest).setOnClickListener { onRecordTestClick() }
        v.findViewById<Button>(R.id.btnShareRecord).setOnClickListener { onShareRecordClick() }
        v.findViewById<Button>(R.id.btnShareLog).setOnClickListener { onShareLogClick() }

        v.findViewById<RadioGroup>(R.id.rgOverlayMode).setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbOverlayOverlay -> Prefs.OVERLAY_MODE_OVERLAY
                R.id.rbOverlayLive -> Prefs.OVERLAY_MODE_LIVE
                else -> Prefs.OVERLAY_MODE_AUTO
            }
            Prefs.get(requireContext()).edit().putString(Prefs.KEY_OVERLAY_MODE, mode).apply()
        }

        updateVoiceUi()
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // view 已就绪再回显配置（含胶囊形态 RadioGroup）
        loadPrefsIntoUi()
    }

    override fun onResume() {
        super.onResume()
        VoiceController.addListener(voiceListener)
        uiHandler.post(statusPoll)
    }

    override fun onPause() {
        super.onPause()
        VoiceController.removeListener(voiceListener)
        uiHandler.removeCallbacks(statusPoll)
    }

    // ---------- 事件 ----------

    private fun onStartClick() {
        saveUiToPrefs()
        startRequested = true
        requestNotificationAndPromotedPermission()
        ConnectionService.start(requireContext())
        FairyOverlayService.ensureRunning(requireContext())
        if (!Settings.canDrawOverlays(requireContext())) {
            Toast.makeText(requireContext(), getString(R.string.overlay_permission_hint), Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${requireContext().packageName}"),
                    )
                )
            }
        }
        refreshStatus()
    }

    private fun onStopClick() {
        startRequested = false
        ConnectionService.stop(requireContext())
        FairyOverlayService.stop(requireContext())
        FairyClientHolder.clear()
        refreshStatus()
    }

    private fun onAddTileClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            TileService.requestListeningState(
                requireContext(),
                ComponentName(requireContext(), FairyVoiceTileService::class.java),
            )
        } else {
            Toast.makeText(
                requireContext(),
                "请手动添加：下拉控制中心 → 编辑 → 添加 Fairy Voice",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun onSendAskClick() {
        val text = etAskInput.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(requireContext(), "先输入指令文本", Toast.LENGTH_SHORT).show()
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
                val reply = client.sendAsk(text)
                uiHandler.post { tvReply.text = "Fairy：${reply.text}" }
            } catch (e: FairyVoiceException) {
                uiHandler.post { tvReply.text = "失败：${e.message}" }
            }
        }.start()
    }

    private fun onRecordTestClick() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            VoiceController.toggle(requireContext())
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun onShareLogClick() {
        val file = File(requireContext().filesDir, "logs/fairy_log.txt")
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(requireContext(), getString(R.string.share_log_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_log_title))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_log_title)))
    }

    private fun onShareRecordClick() {
        val file = lastRecordedFile
        if (file == null || !file.exists()) {
            Toast.makeText(requireContext(), getString(R.string.share_record_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_record_title)))
    }

    private fun updateVoiceUi() {
        val s = VoiceController.currentState
        tvVoiceState.text = getString(
            when (s) {
                VoiceController.State.IDLE -> R.string.voice_state_idle
                VoiceController.State.RECORDING -> R.string.voice_state_recording
                VoiceController.State.RECOGNIZING -> R.string.voice_state_recognizing
                VoiceController.State.WAITING_AI -> R.string.voice_state_waiting_ai
            }
        )
    }

    private fun loadPrefsIntoUi() {
        val p = Prefs.get(requireContext())
        etServerUrl.setText(p.getString(Prefs.KEY_SERVER_URL, "ws://192.168.1.100:8765/ws"))
        etToken.setText(p.getString(Prefs.KEY_TOKEN, ""))
        etSelfId.setText(p.getString(Prefs.KEY_DEVICE_ID, "android-phone"))
        // fairy-voice 协议无 user_id 概念，隐藏该输入框
        etUserId.visibility = View.GONE
        when (p.getString(Prefs.KEY_OVERLAY_MODE, Prefs.OVERLAY_MODE_AUTO)) {
            Prefs.OVERLAY_MODE_OVERLAY -> view?.findViewById<RadioGroup>(R.id.rgOverlayMode)?.check(R.id.rbOverlayOverlay)
            Prefs.OVERLAY_MODE_LIVE -> view?.findViewById<RadioGroup>(R.id.rgOverlayMode)?.check(R.id.rbOverlayLive)
            else -> view?.findViewById<RadioGroup>(R.id.rgOverlayMode)?.check(R.id.rbOverlayAuto)
        }
    }

    private fun saveUiToPrefs() {
        val p = Prefs.get(requireContext()).edit()
        p.putString(Prefs.KEY_SERVER_URL, etServerUrl.text.toString().trim())
        p.putString(Prefs.KEY_TOKEN, etToken.text.toString().trim())
        p.putString(Prefs.KEY_DEVICE_ID, etSelfId.text.toString().trim().ifEmpty { "android-phone" })
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

    private fun requestNotificationAndPromotedPermission() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 36 &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_PROMOTED_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.POST_PROMOTED_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) {
            requestPermissions(perms.toTypedArray(), 100)
        }
    }

    companion object {
        private const val STATUS_POLL_MS = 2_000L
        private const val WAV_HEADER_SIZE = 44L
    }
}
