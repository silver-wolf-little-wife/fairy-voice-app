// SPDX-License-Identifier: AGPL-3.0-only
/**
 * App 前台状态（P3）：MainActivity onResume/onPause 维护。
 * 悬浮窗在 App 前台时不弹出（由对话页气泡承接回复，避免遮挡界面）；
 * App 后台/息屏时弹出悬浮卡片 + 高优先级通知。
 */
package com.fairyvoice.app

object AppState {
    @Volatile
    var foreground: Boolean = false
}
