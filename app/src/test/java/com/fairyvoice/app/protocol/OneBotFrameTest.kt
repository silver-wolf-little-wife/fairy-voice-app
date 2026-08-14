// SPDX-License-Identifier: AGPL-3.0-only
package com.fairyvoice.app.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OneBotFrameTest {

    @Test
    fun `私聊事件帧包含必要字段`() {
        val raw = oneBotPrivateMessageEvent(
            selfId = "10086", userId = "10001", messageId = 1L, text = "今天天气怎么样",
        )
        val o = JSONObject(raw)
        assertEquals("message", o.getString("post_type"))
        assertEquals("private", o.getString("message_type"))
        assertEquals(10086L, o.getLong("self_id"))
        assertEquals(10001L, o.getLong("user_id"))
        assertEquals(1L, o.getLong("message_id"))
        assertTrue(o.getLong("time") > 0)
        assertEquals("今天天气怎么样", o.getString("raw_message"))
    }

    @Test
    fun `事件帧 message 段为 array 格式并含 text 段`() {
        val raw = oneBotPrivateMessageEvent("10086", "10001", 1L, "你好")
        val o = JSONObject(raw)
        val msg = o.getJSONArray("message")
        assertEquals(1, msg.length())
        val seg = msg.getJSONObject(0)
        assertEquals("text", seg.getString("type"))
        assertEquals("你好", seg.getJSONObject("data").getString("text"))
    }

    @Test
    fun `事件帧 sender 字段带数字 user_id`() {
        val raw = oneBotPrivateMessageEvent("10086", "10001", 1L, "hi")
        val sender = JSONObject(raw).getJSONObject("sender")
        assertEquals(10001L, sender.getLong("user_id"))
        assertEquals("FairyVoice", sender.getString("nickname"))
    }

    @Test
    fun `解析 API 请求帧`() {
        val raw = """{"action":"send_private_msg","params":{"user_id":10001,"message":[{"type":"text","data":{"text":"AI回复"}}]},"echo":{"seq":1}}"""
        val req = parseOneBotApiRequest(raw)
        assertNotNull(req)
        req!!
        assertEquals("send_private_msg", req.action)
        assertEquals(10001, req.params.getInt("user_id"))
        val echo = req.echo as JSONObject
        assertEquals(1, echo.getInt("seq"))
    }

    @Test
    fun `解析非 API 帧或垃圾输入返回 null`() {
        assertNull(parseOneBotApiRequest("""{"post_type":"message"}"""))
        assertNull(parseOneBotApiRequest("not-json"))
        assertNull(parseOneBotApiRequest(""))
    }

    @Test
    fun `API 响应帧原样回传 echo`() {
        val data = JSONObject().put("message_id", 999)
        val echo = JSONObject().put("seq", 1)
        val raw = oneBotApiResponse(ok = true, data = data, echo = echo)
        val o = JSONObject(raw)
        assertEquals("ok", o.getString("status"))
        assertEquals(0, o.getInt("retcode"))
        assertEquals(999, o.getJSONObject("data").getInt("message_id"))
        assertEquals(1, o.getJSONObject("echo").getInt("seq"))
    }

    @Test
    fun `失败响应带 retcode 100`() {
        val raw = oneBotApiResponse(ok = false, data = JSONObject(), echo = null)
        val o = JSONObject(raw)
        assertEquals("failed", o.getString("status"))
        assertEquals(100, o.getInt("retcode"))
    }

    @Test
    fun `extractText 从 message 段数组拼接纯文本`() {
        val msg = org.json.JSONArray()
            .put(JSONObject().apply {
                put("type", "text"); put("data", JSONObject().put("text", "第一段"))
            })
            .put(JSONObject().apply {
                put("type", "image"); put("data", JSONObject().put("file", "x"))
            })
            .put(JSONObject().apply {
                put("type", "text"); put("data", JSONObject().put("text", "第二段"))
            })
        assertEquals("第一段第二段", oneBotExtractText(msg))
    }

    @Test
    fun `extractText 对非数组输入回退为字符串`() {
        assertEquals("hi", oneBotExtractText("hi"))
        assertEquals("", oneBotExtractText(null))
    }
}
