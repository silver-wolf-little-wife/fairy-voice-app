// SPDX-License-Identifier: AGPL-3.0-only
/**
 * 控制中心磁贴（Quick Settings Tile）：OPPO 等无系统级按键映射 ROM 的替代唤醒入口。
 * 用户将磁贴加入控制中心后，下拉通知栏点击即可唤醒 Fairy（M4 起直接开始录音）。
 */
package com.fairyvoice.app.wake

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
        WakeTrigger.trigger(this)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val connected = FairyClientHolder.client?.isConnected == true
        tile.state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.subtitle = if (connected) getString(R.string.tile_subtitle_connected)
        else getString(R.string.tile_subtitle_idle)
        tile.updateTile()
    }
}
