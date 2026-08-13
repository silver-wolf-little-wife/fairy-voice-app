// SPDX-License-Identifier: AGPL-3.0-only
/**
 * 全局客户端持有者。ConnectionService 负责创建/常驻，MainActivity 与 WakeKeyService 共用。
 */
package com.fairyvoice.app

import com.fairyvoice.app.protocol.FairyVoiceClient

object FairyClientHolder {
    @Volatile
    var client: FairyVoiceClient? = null
        private set

    @Synchronized
    fun createOrGet(
        serverUrl: String,
        token: String,
        deviceId: String,
        heartbeatMs: Long,
        askTimeoutMs: Long,
    ): FairyVoiceClient {
        val existing = client
        if (existing != null) {
            // 配置未变：复用；配置变了：停掉旧实例重建，否则一直连旧地址/token
            if (existing.serverUrlValue == serverUrl &&
                existing.tokenValue == token &&
                existing.deviceIdValue == deviceId
            ) {
                return existing
            }
            existing.stop()
            client = null
        }
        val c = FairyVoiceClient(
            serverUrl = serverUrl,
            token = token,
            deviceId = deviceId,
            heartbeatIntervalMs = heartbeatMs,
            askTimeoutMs = askTimeoutMs,
        )
        client = c
        return c
    }

    @Synchronized
    fun clear() {
        client?.stop()
        client = null
    }
}
