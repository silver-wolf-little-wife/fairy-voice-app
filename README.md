# fairy-voice-android

fairy-voice 的 C 端 Android 语音终端（M3 里程碑 · 骨架版）。

> 对应 B 端插件仓库 `fairy-voice`（AstrBot 插件）与协议文档 `docs/PROTOCOL.md`。
> 本仓库替代原 Python 桌面版 `fairy-voice-app` 作为手机端终端：WS 常驻连接 + 实体键唤醒，M4 起挂接录音/识别/TTS。

## 架构

```
手机（本 APP，Kotlin）                         B 端（AstrBot 插件，Python）
┌────────────────────────────┐                ┌──────────────────────────┐
│ 无障碍服务监听实体键组合     │                │ aiohttp WS 服务端         │
│   ↓ 触发唤醒               │   WS 主动外连    │   ↓ hello/token 握手      │
│ 前台服务 ConnectionService │ ─────────────▶ │   ↓ ask 请求              │
│   ↓ sendAsk(文本)          │    JSON 帧      │ llm_generate / 记忆策略    │
│   ↓ 收到 response          │ ◀───────────── │   ↓ AI 回复回传           │
│ (M4: TTS 播报)             │                │                          │
└────────────────────────────┘                └──────────────────────────┘
```

## 模块

| 模块 | 说明 |
|---|---|
| `protocol/Protocol.kt` | 协议帧模型（hello/ask/response/hello_ack），纯 Kotlin 可单测 |
| `protocol/FairyVoiceClient.kt` | WS 客户端：握手/心跳/ask/指数退避重连，纯 Kotlin + OkHttp |
| `service/ConnectionService.kt` | 前台常驻服务，持有客户端 |
| `wake/WakeKeyService.kt` | 无障碍服务，实体键组合唤醒 |
| `MainActivity.kt` | 配置 + 状态 + M3 联调入口（手动输入指令触发 ask） |

## 构建

```bash
# 需要 JDK 17+ 与 Android SDK
gradle assembleDebug          # 产物 app/build/outputs/apk/debug/app-debug.apk
gradle testDebugUnitTest      # 协议 + WS 客户端单测（本地 MockWebServer 模拟 B 端）
```

## 使用

1. 安装 APK，填写 B 端地址（`ws://<host>:<port>/ws`）、token、设备 ID。
2. 点「启动连接」，通知栏出现常驻通知即连接成功。
3. 在系统设置开启「Fairy Voice」无障碍服务（实体键唤醒依赖）。
4. 同时按住 **音量上 + 音量下 0.5 秒** 唤醒（M3 唤醒 = 拉起主界面；M4 改为直接开始录音）。
5. M3 联调：在输入框输入指令文本，点「发送 ask」，查看 AI 回复。

## 实体键唤醒的边界（重要）

- **电源键（息屏键）事件被 Android 系统独占**，任何第三方 APP（含前台）都无法监听，因此
  「电源键 + 音量上」无法由 APP 内实现（无 root）。
- 本 APP 使用**无障碍服务**监听音量键组合，亮屏时任意界面可用；不拦截按键（音量调节正常）。
- 息屏状态下第三方 APP 收不到任何按键事件，仅耳机媒体键可唤醒（M4 预留）。
- 部分 ROM 支持把「电源键 + 音量上」映射为启动应用，可近似实现你的需求，步骤见 `docs/WAKEUP.md`。

## 许可

AGPL-3.0-only（与 B 端仓库一致）
