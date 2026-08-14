// SPDX-License-Identifier: AGPL-3.0-only
/**
 * P3 对话消息模型 + 全局历史。
 *
 * ChatHistory 由服务层（VoiceController）写入，保证磁贴/音量键等唤醒产生的对话
 * 即使在对话页未创建/后台时也完整记录；对话页创建时从历史加载显示。
 */
package com.fairyvoice.app

enum class ChatSender { USER, FAIRY }

data class ChatMessage(val text: String, val sender: ChatSender)

object ChatHistory {
    val messages = mutableListOf<ChatMessage>()

    @Synchronized
    fun add(text: String, sender: ChatSender) {
        messages.add(ChatMessage(text, sender))
    }
}
