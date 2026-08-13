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
    )
}
