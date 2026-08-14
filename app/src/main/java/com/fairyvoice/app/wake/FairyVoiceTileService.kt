// SPDX-License-Identifier: AGPL-3.0-only
/**
 * 控制中心磁贴（Quick Settings Tile）：OPPO 等无系统级按键映射 ROM 的替代唤醒入口。
 * 用户将磁贴加入控制中心后，下拉通知栏点击即可唤醒 Fairy（M4-1.2 起直接录音，
 * 不再拉起全屏 App；状态与 AI 回复走悬浮窗/流体云）。
 *
 * ⚠️ 磁贴语义（M4-1.1）：磁贴是「动作按钮」不是开关，恒保持 STATE_INACTIVE（暗态可点击），
 * 连接/录音状态只放进副标题，避免「连接成功后就一直亮着」的误导。
 */
package com.fairyvoice.app.wake

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.fairyvoice.app.OneBotHolder
import com.fairyvoice.app.R
import com.fairyvoice.app.audio.VoiceController

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
        // M4-1.2：磁贴点击直接唤醒（悬浮窗/流体云 + 录音），不拉起全屏 App。
        // 系统会自动收起控制中心面板。
        WakeTrigger.trigger(this)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.subtitle = getString(
            when {
                VoiceController.currentState == VoiceController.State.RECORDING ->
                    R.string.tile_subtitle_recording
                OneBotHolder.client?.isConnected == true ->
                    R.string.tile_subtitle_connected
                else -> R.string.tile_subtitle_idle
            }
        )
        tile.updateTile()
    }
}
