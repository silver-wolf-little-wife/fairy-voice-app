// SPDX-License-Identifier: AGPL-3.0-only
/**
 * fairy-voice 协议帧模型（v2.0 流式，见 B 端仓库 docs/PROTOCOL.md）。
 * v2.0（2026-08-17）：新增流式响应三类帧 stream_begin / stream_delta / stream_end；
 * client_version 升 0.2.0；单帧 response 仅作 B 端非流式兜底保留。
 * 纯 Kotlin + org.json，不依赖 Android API，可在 JVM 单测。
 */
package com.fairyvoice.app.protocol

import org.json.JSONObject

const val PROTOCOL_VERSION = "0.2.0"

/** C → B：握手帧 */
fun helloFrame(token: String, deviceId: String): String = JSONObject().apply {
    put("type", "hello")
    put("token", token)
    put("device_id", deviceId)
    put("client_version", PROTOCOL_VERSION)
}.toString()

/** C → B：心跳帧 */
fun pingFrame(): String = """{"type":"ping"}"""

/** C → B：文本指令请求帧 */
fun askFrame(id: String, text: String, lang: String): String = JSONObject().apply {
    put("type", "ask")
    put("id", id)
    put("text", text)
    put("lang", lang)
}.toString()

/** C → B：语音指令请求帧（M4-2，协议 v1.1.0）——音频由 B 端 ASR 识别后走 ask 链路 */
fun voiceAskFrame(id: String, audioBase64: String, lang: String): String = JSONObject().apply {
    put("type", "voice_ask")
    put("id", id)
    put("audio", audioBase64)
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

/** B → C：ask / voice_ask 响应帧（非流式兜底；错误仍走此帧） */
data class ResponseFrame(
    val id: String,
    val ok: Boolean,
    val text: String?,
    val recognized: String?,
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
                recognized = data?.optString("recognized")?.ifEmpty { null },
                errorCode = err?.optString("code"),
                errorMessage = err?.optString("message"),
            )
        }.getOrElse { ResponseFrame("", false, null, null, "bad_frame", "无法解析响应") }
    }
}

// ---------- v2.0 流式帧 ----------

/** B → C：流开始帧（v2.0）。标志该 id 进入流式状态，必须先于任何 delta。 */
data class StreamBeginFrame(
    val id: String,
    val recognized: String?,
) {
    companion object {
        fun parse(raw: String): StreamBeginFrame = runCatching {
            val o = JSONObject(raw)
            StreamBeginFrame(
                id = o.optString("id"),
                recognized = o.optString("recognized").ifEmpty { null },
            )
        }.getOrElse { StreamBeginFrame("", null) }
    }
}

/** B → C：流增量帧（v2.0）。delta 为增量文本，C 端直接追加展示。 */
data class StreamDeltaFrame(
    val id: String,
    val delta: String,
) {
    companion object {
        fun parse(raw: String): StreamDeltaFrame = runCatching {
            val o = JSONObject(raw)
            StreamDeltaFrame(
                id = o.optString("id"),
                delta = o.optString("delta"),
            )
        }.getOrElse { StreamDeltaFrame("", "") }
    }
}

/** B → C：流结束帧（v2.0）。ok=true 时 data.text 为完整回复；ok=false 携带错误。 */
data class StreamEndFrame(
    val id: String,
    val ok: Boolean,
    val text: String?,
    val errorCode: String?,
    val errorMessage: String?,
) {
    companion object {
        fun parse(raw: String): StreamEndFrame = runCatching {
            val o = JSONObject(raw)
            val data = o.optJSONObject("data")
            val err = o.optJSONObject("error")
            StreamEndFrame(
                id = o.optString("id"),
                ok = o.optBoolean("ok", false),
                text = data?.optString("text")?.ifEmpty { null },
                errorCode = err?.optString("code"),
                errorMessage = err?.optString("message"),
            )
        }.getOrElse { StreamEndFrame("", false, null, "bad_frame", "无法解析流结束帧") }
    }
}
