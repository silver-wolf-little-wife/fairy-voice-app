// SPDX-License-Identifier: AGPL-3.0-only
/**
 * OneBot 11 反向 WS 帧模型（C 端最小客户端）。
 *
 * 依据 P0-1 实测确认的 aiocqhttp 1.4.4 协议：
 * - 事件上报：私聊消息事件（message 段 array 格式），走 universal 单连接 C→B。
 * - API 调用：B→C 下发 `{action, params, echo}`，C 处理后回 `{status, retcode, data, echo}`（echo 原样回传）。
 * - 本文件只负责帧的构造/解析，纯 Kotlin + org.json，可在 JVM 单测。
 */
package com.fairyvoice.app.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * 构造私聊文本消息事件上报帧（OneBot 11，message 段 array 格式）。
 * @param selfId 机器人自身 ID（纯数字字符串）
 * @param userId 发送者 ID（纯数字字符串，C 端固定）
 * @param messageId 自增消息 ID
 * @param text 文本内容（ASR 识别结果 / 手动输入）
 * @param time Unix 秒
 */
fun oneBotPrivateMessageEvent(
    selfId: String,
    userId: String,
    messageId: Long,
    text: String,
    time: Long = System.currentTimeMillis() / 1000,
): String = JSONObject().apply {
    put("post_type", "message")
    put("message_type", "private")
    put("time", time)
    put("self_id", selfId.toLongOrNull() ?: 0L)
    put("message_id", messageId)
    put("user_id", userId.toLongOrNull() ?: 0L)
    put("message", JSONArray().put(
        JSONObject().apply {
            put("type", "text")
            put("data", JSONObject().put("text", text))
        }
    ))
    put("raw_message", text)
    put("sender", JSONObject().apply {
        put("user_id", userId.toLongOrNull() ?: 0L)
        put("nickname", "FairyVoice")
    })
}.toString()

/** B → C 的 API 调用（反向 WS 下由 AstrBot 下发）。 */
data class OneBotApiRequest(
    val action: String,
    val params: JSONObject,
    /** 原样回传的 echo（aiocqhttp 实测为 `{"seq": N}`）。 */
    val echo: Any?,
)

/** 解析 B → C 的 API 调用帧；非 API 帧返回 null（解析失败安全兜底，不抛异常）。 */
fun parseOneBotApiRequest(raw: String): OneBotApiRequest? = runCatching {
    val o = JSONObject(raw)
    val action = o.optString("action")
    if (action.isEmpty()) return null
    OneBotApiRequest(
        action = action,
        params = o.optJSONObject("params") ?: JSONObject(),
        echo = o.opt("echo"),
    )
}.getOrNull()

/**
 * 构造 API 响应帧（C → B，回传给 AstrBot）。
 * @param ok 是否成功（status=ok/retcode=0 或 status=failed/retcode=100）
 * @param data 响应数据
 * @param echo 必须原样回传请求里的 echo
 */
fun oneBotApiResponse(
    ok: Boolean,
    data: JSONObject,
    echo: Any?,
    retcode: Int = if (ok) 0 else 100,
): String = JSONObject().apply {
    put("status", if (ok) "ok" else "failed")
    put("retcode", retcode)
    put("data", data)
    if (echo != null) put("echo", echo)
}.toString()

/** 从 OneBot 消息段（array 或纯文本）提取纯文本，供 send_private_msg 回复展示用。 */
fun oneBotExtractText(message: Any?): String = when (message) {
    is JSONArray -> {
        val sb = StringBuilder()
        for (i in 0 until message.length()) {
            val seg = message.optJSONObject(i) ?: continue
            if (seg.optString("type") == "text") {
                sb.append(seg.optJSONObject("data")?.optString("text") ?: "")
            }
        }
        sb.toString()
    }
    else -> message?.toString() ?: ""
}
