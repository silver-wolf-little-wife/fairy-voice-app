// SPDX-License-Identifier: AGPL-3.0-only
/**
 * 控制中心磁贴（Quick Settings Tile）：OPPO 等无系统级按键映射 ROM 的替代唤醒入口。
 * 用户将磁贴加入控制中心后，下拉通知栏点击即可唤醒 Fairy（M4 起直接开始录音）。
 *
 * ⚠️ 磁贴语义（M4-1.1）：磁贴是「动作按钮」不是开关，恒保持 STATE_INACTIVE（暗态可点击），
 * 连接状态只放进副标题，避免「连接成功后就一直亮着」的误导。
 */
package com.fairyvoice.app.wake

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.fairyvoice.app.FairyClientHolder
import com.fairyvoice.app.R

class FairyVoiceTileService : TileService() {

    override fun onStartListening() {
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            // 锁屏态不响应，防止误触；解锁后由系统重新展示
            return
        }
        // 拉起主界面并收起控制中心面板；Intent 带 WAKE action，由
        // MainActivity.onNewIntent/onCreate 直接触发录音。
        // API 34+ 弃用 Intent 变体，改用 PendingIntent 变体（API 28+）；API 26/27 走旧接口。
        val wake = WakeTrigger.wakeIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val pi = PendingIntent.getActivity(this, 0, wake, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(wake)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        val connected = FairyClientHolder.client?.isConnected == true
        tile.subtitle = getString(
            if (connected) R.string.tile_subtitle_connected else R.string.tile_subtitle_idle
        )
        tile.updateTile()
    }
}
