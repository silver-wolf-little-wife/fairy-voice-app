# fairy-voice-android

fairy-voice 语音助手的 **C 端 Android 语音终端**。

自研协议 v2.0 流式直连 B 端（[fairy-voice](https://github.com/silver-wolf-little-wife/fairy-voice)），
本地 ASR（sherpa-onnx），流式输出（打字机效果），无 TTS。

## 架构

```
手机（C 端）                                    B 端（AstrBot 插件 fairy-voice）
┌──────────────────────────────┐  WS 主动外连   ┌──────────────────────────────┐
│ 唤醒(音量键/磁贴/通知栏) → 录音 │ ────────────▶ │ aiohttp WS 服务端             │
│   ↓ 本地 ASR (sherpa-onnx)    │              │   ↓ Provider.text_chat_stream │
│   ↓ 识别文本 → ask 帧          │              │   ↓ 工具循环 / 记忆策略        │
│ FairyVoiceClient (OkHttp WS)  │ ◀──────────── │ stream_begin/delta/end 流式  │
│   ↓ 逐段上屏(对话气泡/悬浮卡)  │              │                              │
└──────────────────────────────┘              └──────────────────────────────┘
```

## 功能

- **流式输出**：AI 回复逐 token 上屏（打字机效果），首 token 即见，降低停顿感
- **本地 ASR**：sherpa-onnx + paraformer-zh-small（74MB），识别不出手机，隐私 + 离线
- **流式健壮性**：首 token 超时 60s / 增量间空闲超时 60s（适配工具调用/长思考）/ 断流兜底（已收内容完整可读）/ 迟到帧防重复
- **多入口唤醒**：实体音量键组合（无障碍服务）/ 控制中心磁贴 / 通知栏按钮
- **悬浮窗 + 流体云**：App 后台时以悬浮胶囊 + 展开卡片呈现状态与 AI 回复
- **对话历史**：全局对话记录，磁贴/音量键等非主界面唤醒产生的聊天也完整显示
- **工具调用**：B 端 enable_tools 开启后可调用 AstrBot 已注册工具（米家/远程电脑等）

## 构建

```bash
# 需要 JDK 17~22（本机用 jbr-21.0.11）与 Android SDK（compileSdk 37）
set "JAVA_HOME=C:\Users\13370\.jdks\jbr-21.0.11"
gradlew.bat assembleDebug          # 产物 app/build/outputs/apk/debug/app-debug.apk
gradlew.bat assembleRelease        # 签名 Release 包（需配置 keystore）
gradlew.bat testDebugUnitTest      # 协议 + WS 客户端单测（本地 MockWebServer 模拟 B 端）
```

## 使用

1. B 端部署：安装 [fairy-voice](https://github.com/silver-wolf-little-wife/fairy-voice) AstrBot 插件，启动 WS 服务
2. 安装 APK，设置页填写 B 端地址（`ws://<host>:8766/ws`）、token、设备 ID
3. 点「启动连接」，通知栏出现常驻通知即连接成功
4. 在系统设置开启「Fairy Voice」无障碍服务（实体键唤醒依赖）
5. **音量上 + 音量下 0.5 秒** 唤醒，或下拉控制中心点磁贴唤醒
6. 语音指令：唤醒 → 录音 → 本地识别 → 流式上屏 AI 回复
7. 文字指令：对话页输入文本，点发送，流式显示回复

## 实体键唤醒

- **电源键事件被 Android 系统独占**，第三方 APP 无法监听，因此「电源键 + 音量上」无法实现（无 root）
- 本 APP 使用**无障碍服务**监听音量键组合，亮屏时任意界面可用；不拦截按键
- 息屏状态下收不到按键事件，仅耳机媒体键可唤醒
- 部分 ROM 支持电源键映射启动应用，步骤见 `docs/WAKEUP.md`

## 模块

| 模块 | 说明 |
|---|---|
| `protocol/FairyVoiceClient.kt` | 协议 v2.0 流式 WS 客户端：握手/心跳/ask/流式聚合/断流兜底/超时看门狗 |
| `protocol/Protocol.kt` | 帧模型：hello/ask/response + stream_begin/delta/end，纯 JVM 可测 |
| `audio/OnnxAsr.kt` | 本地 ASR（sherpa-onnx + paraformer-zh-small） |
| `audio/VoiceController.kt` | 语音链路状态机：录音→ASR→ask→流式上屏，驱动对话历史与悬浮窗 |
| `service/FairyOverlayService.kt` | 悬浮窗/流体云前台服务：胶囊 + 展开卡片，流式增量同步 |
| `ChatFragment.kt` / `ChatAdapter` | 对话页：流式消息增量渲染（打字机光标 ▌） |
| `protocol/OneBotClient.kt` | ~~OneBot V11 客户端~~（冻结为回退，主流程不再使用） |

## 许可

AGPL-3.0-only
