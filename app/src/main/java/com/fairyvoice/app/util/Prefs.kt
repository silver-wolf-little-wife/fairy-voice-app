// SPDX-License-Identifier: AGPL-3.0-only
/**
 * 配置存储。token 使用 EncryptedSharedPreferences（对齐 PROTOCOL.md 第 8 节安全要求），
 * 初始化失败时降级为普通 SharedPreferences（如个别模拟器）。
 */
package com.fairyvoice.app.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Prefs {
    private const val FILE = "fairy_voice_prefs"

    const val KEY_SERVER_URL = "server_url"
    const val KEY_TOKEN = "token"
    const val KEY_DEVICE_ID = "device_id"
    const val KEY_HEARTBEAT_MS = "heartbeat_ms"
    const val KEY_ASK_TIMEOUT_MS = "ask_timeout_ms"
    const val KEY_WAKE_COMBO = "wake_combo"

    // P1（OneBot 直连方案）：AstrBot 连接参数
    const val KEY_ASTRBOT_URL = "astrbot_url"
    const val KEY_ASTRBOT_TOKEN = "astrbot_token"
    const val KEY_ONEBOT_SELF_ID = "onebot_self_id"
    const val KEY_ONEBOT_USER_ID = "onebot_user_id"

    /** M4-1.3：胶囊呈现形态（悬浮窗 / 流体云 / 智能），见 OVERLAY_MODE_*。 */
    const val KEY_OVERLAY_MODE = "overlay_mode"
    const val OVERLAY_MODE_AUTO = "auto"
    const val OVERLAY_MODE_OVERLAY = "overlay"
    const val OVERLAY_MODE_LIVE = "live"

    const val COMBO_VOL_UP_DOWN = "vol_up+vol_down"
    const val COMBO_VOL_UP_LONG = "vol_up_long"

    fun get(context: Context): SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun defaults(): Map<String, String> = mapOf(
        KEY_SERVER_URL to "ws://192.168.1.100:8765/ws",
        KEY_DEVICE_ID to "android-phone",
        KEY_HEARTBEAT_MS to "15000",
        KEY_ASK_TIMEOUT_MS to "60000",
        KEY_WAKE_COMBO to COMBO_VOL_UP_DOWN,
        KEY_OVERLAY_MODE to OVERLAY_MODE_AUTO,
        // P1：AstrBot OneBot 连接默认值（端口 6199 = aiocqhttp 默认 ws_reverse_port）
        KEY_ASTRBOT_URL to "ws://192.168.1.100:6199/ws",
        KEY_ASTRBOT_TOKEN to "",
        KEY_ONEBOT_SELF_ID to "10086",
        KEY_ONEBOT_USER_ID to "10001",
    )
}
