# Fairy Voice Android：唤醒方案说明

## 结论速览

| 方案 | 是否实现 | 适用机型 | 限制 |
|---|---|---|---|
| A. 音量上+音量下 同按 0.5s（无障碍） | ✅ 已实现（默认） | 所有 Android 8+ | 仅亮屏生效；音量键事件不拦截 |
| B. 系统级「电源键+音量上」映射启动 App | ❌ OPPO 不可用 | 仅三星 One UI 等少数 ROM | 见下文 OPPO 调研 |
| C. 控制中心磁贴（Quick Settings Tile） | ✅ 已实现 | 所有 Android 8+ | 需手动把磁贴拖入控制中心；点击需亮屏 |
| D. 通知栏常驻通知「唤醒」按钮 | ✅ 已实现 | 所有 Android 8+ | 依赖前台服务通知 |

推荐组合：**A（实体键，亮屏任意界面）+ C（控制中心磁贴，替代电源键组合）**。

---

## OPPO / ColorOS 调研结论（2026-08）

**「电源键+音量上」映射启动任意第三方应用：OPPO 不支持。**

- ColorOS 的「侧键快捷」（`设置 → 便捷工具 → 侧边栏/侧键快捷`）只能把**长按电源键**绑定到**系统预置功能列表**（健康码、付款码、录音等），无法选择任意第三方 App。
- 双击电源键固定打开相机（不可自定义）。
- 黑屏手势（画 O/V/M）仅支持相机、手电筒、小布助手等系统功能。
- 音量键仅负责音量调节，无自定义映射入口。

因此 OPPO 上第二层替代不可行，采用 **C. 控制中心磁贴** 作为替代唤醒入口。

## 方案 C：控制中心磁贴使用说明

1. 安装并打开 Fairy Voice，进入「唤醒方式 → 控制中心磁贴」。
2. Android 13+：点「添加到控制中心」按钮，系统会弹出添加入口，跟随引导完成。
3. 其它版本：下拉通知栏 → 点右上角铅笔图标编辑 → 把「Fairy Voice」磁贴拖入常用区域。
4. 使用：下拉通知栏 → 点击磁贴，即触发唤醒（拉起主界面并广播 `com.fairyvoice.app.action.WAKE`，M4 起直接开始录音）。

磁贴状态：已连接 B 端时显示「已连接」（高亮），否则显示「点击唤醒」。

## 方案 A：实体键唤醒（默认）

- 开启：`设置 → 无障碍 → 已安装的服务 → Fairy Voice 实体键唤醒`（或在 App 内点按钮直达无障碍设置页）。
- 触发：**音量上 + 音量下 同时按住 0.5 秒**（亮屏时任意界面生效）。
- 设计：不消费按键事件（`onKeyEvent` 恒返回 `false`），音量调节不受影响；触发后 2 秒防抖；锁屏/息屏下音量键无法唤醒（系统限制）。

## 为什么 APP 无法监听电源键

Android 的 `KEYCODE_POWER` 事件由系统（PhoneWindowManager）独占处理，普通第三方 APP 与无障碍服务均收不到电源键的按键事件。任何声称无需 root/无需系统权限即可自定义「电源键+音量」组合的 APP 均为伪方案或依赖厂商 ROM 接口。root / Shizuku / Xposed 可绕过，但不属于本项目的支持范围。

## 代码位置

- 实体键：`app/src/main/java/com/fairyvoice/app/wake/WakeKeyService.kt`（无障碍服务）
- 统一唤醒入口：同文件内 `WakeTrigger`（磁贴/无障碍/通知按钮共用）
- 控制中心磁贴：`app/src/main/java/com/fairyvoice/app/wake/FairyVoiceTileService.kt`
- 通知按钮：`app/src/main/java/com/fairyvoice/app/service/ConnectionService.kt`
