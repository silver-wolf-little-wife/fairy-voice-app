// SPDX-License-Identifier: AGPL-3.0-only
/**
 * P3 对话页（千问风格）：聊天气泡 + 文本/语音输入。
 * - 语音：麦克风 → VoiceController.startWake（录音 → 本地 ASR → OneBot 上报 → 回复）
 * - 文本：直接 OneBotHolder.client.sendPrivateMessage
 * - 同时监听 VoiceController：onReply（语音回复）/ onPush（AstrBot 主动消息）/ onError
 */
package com.fairyvoice.app

import android.Manifest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fairyvoice.app.audio.VoiceController
import com.fairyvoice.app.protocol.OneBotException
import java.io.File

class ChatFragment : Fragment() {

    enum class Sender { USER, FAIRY }

    data class ChatMessage(val text: String, val sender: Sender)

    private lateinit var rvChat: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvState: TextView
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter

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
                VoiceController.startWake(requireContext())
            } else {
                Toast.makeText(requireContext(), "未授予麦克风权限，无法语音输入", Toast.LENGTH_SHORT).show()
            }
        }

    private val voiceListener = object : VoiceController.Listener {
        override fun onStateChanged(state: VoiceController.State) {
            uiHandler.post { updateStateText() }
        }

        override fun onRecorded(file: File, hadSpeech: Boolean) {
            // 用户气泡由 onRecognized 追加
        }

        override fun onRecognized(text: String) {
            // P3：识别完成立即显示用户气泡（不等 AI 回复）
            uiHandler.post { addMessage(ChatMessage(text, Sender.USER)) }
        }

        override fun onReply(text: String, recognized: String?) {
            uiHandler.post {
                // 语音链路：用户气泡已在 onRecognized 显示，这里只加 Fairy 回复
                addMessage(ChatMessage(text, Sender.FAIRY))
            }
        }

        override fun onPush(text: String) {
            uiHandler.post { addMessage(ChatMessage(text, Sender.FAIRY)) }
        }

        override fun onError(e: Exception) {
            uiHandler.post { addMessage(ChatMessage("出错了：${e.message}", Sender.FAIRY)) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v = inflater.inflate(R.layout.fragment_chat, container, false)
        rvChat = v.findViewById(R.id.rvChat)
        etInput = v.findViewById(R.id.etChatInput)
        tvStatus = v.findViewById(R.id.tvChatStatus)
        tvState = v.findViewById(R.id.tvChatState)
        val btnMic = v.findViewById<Button>(R.id.btnChatMic)
        val btnSend = v.findViewById<Button>(R.id.btnChatSend)

        adapter = ChatAdapter(messages)
        rvChat.layoutManager = LinearLayoutManager(requireContext())
        rvChat.adapter = adapter

        btnSend.setOnClickListener { onSendClick() }
        btnMic.setOnClickListener { onMicClick() }
        etInput.setOnEditorActionListener { _, _, _ -> onSendClick(); true }
        return v
    }

    override fun onResume() {
        super.onResume()
        VoiceController.addListener(voiceListener)
        uiHandler.post(statusPoll)
        updateStateText()
    }

    override fun onPause() {
        super.onPause()
        VoiceController.removeListener(voiceListener)
        uiHandler.removeCallbacks(statusPoll)
    }

    private fun onSendClick() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return
        etInput.setText("")
        addMessage(ChatMessage(text, Sender.USER))
        Thread {
            try {
                val client = OneBotHolder.client
                if (client == null || !client.isConnected) throw OneBotException.NotConnected()
                val reply = client.sendPrivateMessage(text)
                uiHandler.post { addMessage(ChatMessage(reply, Sender.FAIRY)) }
            } catch (e: Exception) {
                uiHandler.post { addMessage(ChatMessage("失败：${e.message}", Sender.FAIRY)) }
            }
        }.start()
    }

    private fun onMicClick() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            VoiceController.startWake(requireContext())
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        adapter.notifyItemInserted(messages.size - 1)
        rvChat.scrollToPosition(messages.size - 1)
    }

    private fun refreshStatus() {
        val client = OneBotHolder.client
        tvStatus.text = when {
            client?.isConnected == true -> getString(R.string.status_connected)
            client != null -> getString(R.string.status_connecting)
            else -> getString(R.string.status_disconnected)
        }
    }

    private fun updateStateText() {
        tvState.text = getString(
            when (VoiceController.currentState) {
                VoiceController.State.IDLE -> R.string.voice_state_idle
                VoiceController.State.RECORDING -> R.string.voice_state_recording
                VoiceController.State.RECOGNIZING -> R.string.voice_state_recognizing
                VoiceController.State.WAITING_AI -> R.string.voice_state_waiting_ai
                VoiceController.State.SPEAKING -> R.string.voice_state_speaking
            }
        )
    }

    companion object {
        private const val STATUS_POLL_MS = 2_000L
    }
}

/** 聊天气泡适配器。 */
class ChatAdapter(private val items: List<ChatFragment.ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.Holder>() {

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val root: LinearLayout = v.findViewById(R.id.itemRoot)
        val bubble: TextView = v.findViewById(R.id.tvBubble)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val m = items[position]
        holder.bubble.text = m.text
        if (m.sender == ChatFragment.Sender.USER) {
            holder.root.gravity = Gravity.END
            holder.bubble.setBackgroundResource(R.drawable.bubble_user)
        } else {
            holder.root.gravity = Gravity.START
            holder.bubble.setBackgroundResource(R.drawable.bubble_fairy)
        }
    }

    override fun getItemCount(): Int = items.size
}
