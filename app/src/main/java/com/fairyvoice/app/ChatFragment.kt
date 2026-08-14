// SPDX-License-Identifier: AGPL-3.0-only
/**
 * P3 对话页（千问风格）：聊天气泡 + 文本/语音输入。
 * 对话记录存全局 ChatHistory（VoiceController 服务层写入），对话页从历史加载，
 * 因此磁贴/音量键等唤醒产生的对话即使本页未创建也能完整显示。
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

    private lateinit var rvChat: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvState: TextView
    private val messages: MutableList<ChatMessage> get() = ChatHistory.messages
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
            // 历史已由 VoiceController 写入，这里只刷新 UI
            uiHandler.post { notifyNewMessage() }
        }

        override fun onReply(text: String, recognized: String?) {
            uiHandler.post { notifyNewMessage() }
        }

        override fun onPush(text: String) {
            uiHandler.post { notifyNewMessage() }
        }

        override fun onError(e: Exception) {
            uiHandler.post {
                addMessage("出错了：${e.message}", ChatSender.FAIRY)
            }
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
        // 加载历史后滚动到底
        rvChat.post {
            if (messages.isNotEmpty()) rvChat.scrollToPosition(messages.size - 1)
        }

        btnSend.setOnClickListener { onSendClick() }
        btnMic.setOnClickListener { onMicClick() }
        etInput.setOnEditorActionListener { _, _, _ -> onSendClick(); true }
        updateStateText()
        return v
    }

    override fun onResume() {
        super.onResume()
        VoiceController.addListener(voiceListener)
        uiHandler.post(statusPoll)
        // P3：回到前台全量刷新——后台期间（磁贴/音量键唤醒、主动推送）新增的全局历史显示出来
        adapter.notifyDataSetChanged()
        if (messages.isNotEmpty()) rvChat.scrollToPosition(messages.size - 1)
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
        addMessage(text, ChatSender.USER)
        // 文字指令不走 VoiceController 状态机，手动更新左下角状态
        tvState.text = getString(R.string.voice_state_waiting_ai)
        Thread {
            try {
                val client = OneBotHolder.client
                if (client == null || !client.isConnected) throw OneBotException.NotConnected()
                val reply = client.sendPrivateMessage(text)
                uiHandler.post {
                    addMessage(reply, ChatSender.FAIRY)
                    updateStateText()
                }
            } catch (e: Exception) {
                uiHandler.post {
                    addMessage("失败：${e.message}", ChatSender.FAIRY)
                    updateStateText()
                }
            }
        }.start()
    }

    private fun onMicClick() {
        // 前置检查连接，避免进入语音链路后长时间等连接
        if (OneBotHolder.client?.isConnected != true) {
            Toast.makeText(requireContext(), "未连接 AstrBot，请先在设置页启动连接", Toast.LENGTH_SHORT).show()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            VoiceController.startWake(requireContext())
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** 写全局历史 + 刷新 UI。 */
    private fun addMessage(text: String, sender: ChatSender) {
        ChatHistory.add(text, sender)
        notifyNewMessage()
    }

    /** 历史已被服务层写入，仅刷新最后一条。 */
    private fun notifyNewMessage() {
        if (messages.isEmpty()) return
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
class ChatAdapter(private val items: List<ChatMessage>) :
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
        if (m.sender == ChatSender.USER) {
            holder.root.gravity = Gravity.END
            holder.bubble.setBackgroundResource(R.drawable.bubble_user)
        } else {
            holder.root.gravity = Gravity.START
            holder.bubble.setBackgroundResource(R.drawable.bubble_fairy)
        }
    }

    override fun getItemCount(): Int = items.size
}
