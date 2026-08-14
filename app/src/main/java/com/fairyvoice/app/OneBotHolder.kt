// SPDX-License-Identifier: AGPL-3.0-only
/**
 * OneBot 客户端全局持有者（P1）。ConnectionService 负责创建/常驻，MainActivity 等共用。
 * 替代 FairyClientHolder（老方案自研协议客户端，冻结保留作回退）。
 */
package com.fairyvoice.app

import com.fairyvoice.app.protocol.OneBotClient

object OneBotHolder {
    @Volatile
    var client: OneBotClient? = null
        private set

    @Synchronized
    fun createOrGet(
        serverUrl: String,
        token: String,
        selfId: String,
        userId: String,
    ): OneBotClient {
        val existing = client
        if (existing != null) {
            // 配置未变：复用；配置变了：停旧实例重建，否则一直连旧地址
            if (existing.serverUrlValue == serverUrl &&
                existing.tokenValue == token &&
                existing.selfIdValue == selfId &&
                existing.userIdValue == userId
            ) {
                return existing
            }
            existing.stop()
            client = null
        }
        val c = OneBotClient(
            serverUrl = serverUrl,
            token = token,
            selfId = selfId,
            userId = userId,
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
