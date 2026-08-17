// SPDX-License-Identifier: AGPL-3.0-only
/**
 * P3 对话消息模型 + 全局历史。
 *
 * ChatHistory 由服务层（VoiceController）写入，保证磁贴/音量键等唤醒产生的对话
 * 即使在对话页未创建/后台时也完整记录；对话页创建时从历史加载显示。
 *
 * S3（v2.0 流式）：ChatMessage 增加 id 与 finalized 字段——
 * 流式回复以 finalized=false 的消息开始，增量经 appendStreaming 追加，
 * stream_end 以完整文本 finalizeStreaming 收尾。finalized=false 期间 UI 可作打字机/未完成态渲染。
 */
package com.fairyvoice.app

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

enum class ChatSender { USER, FAIRY }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    var text: String,
    val sender: ChatSender,
    /** false = 流式进行中（增量上屏中），true = 已收尾。 */
    var finalized: Boolean = true,
)

object ChatHistory {
    /** 线程安全列表：服务层（VoiceController 线程）写入 + 主线程（RecyclerView）读取并发安全。 */
    val messages: MutableList<ChatMessage> = CopyOnWriteArrayList()

    /** 追加一条完整消息，返回消息对象。 */
    fun add(text: String, sender: ChatSender): ChatMessage {
        val m = ChatMessage(text = text, sender = sender)
        messages.add(m)
        return m
    }

    /** 追加一条流式消息（finalized=false，文本为空），返回消息对象供增量关联。 */
    fun addStreaming(sender: ChatSender): ChatMessage {
        val m = ChatMessage(text = "", sender = sender, finalized = false)
        messages.add(m)
        return m
    }

    /** 流式增量追加到指定 id 的消息；未找到返回 null。 */
    fun appendStreaming(id: String, delta: String): ChatMessage? {
        val m = messages.firstOrNull { it.id == id } ?: return null
        synchronized(m) { m.text += delta }
        return m
    }

    /** 流式结束：以最终文本（data.text 兜底，可修复丢帧）覆盖并置为 finalized。 */
    fun finalizeStreaming(id: String, finalText: String): ChatMessage? {
        val m = messages.firstOrNull { it.id == id } ?: return null
        synchronized(m) {
            m.text = finalText
            m.finalized = true
        }
        return m
    }
}
