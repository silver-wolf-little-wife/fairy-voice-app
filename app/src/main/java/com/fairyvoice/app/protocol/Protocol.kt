// SPDX-License-Identifier: AGPL-3.0-only
/**
 * fairy-voice 协议帧模型（v1.0.0，见 B 端仓库 docs/PROTOCOL.md）。
 * 纯 Kotlin + org.json，不依赖 Android API，可在 JVM 单测。
 */
package com.fairyvoice.app.protocol

import org.json.JSONObject

const val PROTOCOL_VERSION = "0.1.0"

/** C → B：握手帧 */
fun helloFrame(token: String, deviceId: String): String = JSONObject().apply {
    put("type", "hello")
    put("token", token)
    put("device_id", deviceId)
    put("client_version", PROTOCOL_VERSION)
}.toString()

/** C → B：心跳帧 */
fun pingFrame(): String = """{"type":"ping"}"""

/** C → B：语音指令请求帧 */
fun askFrame(id: String, text: String, lang: String): String = JSONObject().apply {
    put("type", "ask")
    put("id", id)
    put("text", text)
    put("lang", lang)
}.toString()

/** B → C：握手应答 */
data class HelloAck(
    val ok: Boolean,
    val sessionId: String?,
    val error: String?,
) {
    companion object {
        fun parse(raw: String): HelloAck = runCatching {
            val o = JSONObject(raw)
            HelloAck(
                ok = o.optBoolean("ok", false),
                sessionId = o.optString("session_id").ifEmpty { null },
                error = o.optString("error").ifEmpty { null },
            )
        }.getOrElse { HelloAck(ok = false, sessionId = null, error = "bad_frame") }
    }
}

/** B → C：ask 响应帧 */
data class ResponseFrame(
    val id: String,
    val ok: Boolean,
    val text: String?,
    val errorCode: String?,
    val errorMessage: String?,
) {
    companion object {
        fun parse(raw: String): ResponseFrame = runCatching {
            val o = JSONObject(raw)
            val data = o.optJSONObject("data")
            val err = o.optJSONObject("error")
            ResponseFrame(
                id = o.optString("id"),
                ok = o.optBoolean("ok", false),
                text = data?.optString("text"),
                errorCode = err?.optString("code"),
                errorMessage = err?.optString("message"),
            )
        }.getOrElse { ResponseFrame("", false, null, "bad_frame", "无法解析响应") }
    }
}
